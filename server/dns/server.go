package main

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"sync"
	"time"

	"github.com/miekg/dns"
)

// Config de uma máquina de DNS. Todas as máquinas usam a MESMA config (só muda o IP onde rodam): somar uma região
// nova = subir o mesmo programa em outra máquina e acrescentar o nome dela em NS (aqui e no registro do domínio).
type Config struct {
	Zone       string   // "lan.sofaly.tv."
	NS         []string // "ns1.sofaly.tv.", "ns2.sofaly.tv." ...
	Hostmaster string   // "hostmaster.sofaly.tv." (e-mail do SOA, com ponto no lugar do @)
	API        string   // "https://api.sofaly.tv": de onde vem o TXT do desafio
	Serial     uint32
}

const (
	ttlAddress   = 86400 // o IP está no nome e nunca muda: pode guardar por 1 dia
	ttlChallenge = 10    // o Let's Encrypt precisa ver o TXT novo logo
	ttlZone      = 3600
)

// TxtSource busca os desafios de uma TV. Interface para o teste trocar o api por um fake.
type TxtSource interface {
	Txt(ctx context.Context, id string) ([]string, error)
}

// APITxt lê GET /v1/dns/txt/<id> do api, com memória de 5 s (o Let's Encrypt consulta várias vezes
// e de vários lugares; isso poupa requests sem atrasar a validação).
type APITxt struct {
	URL    string
	Client *http.Client
	mu     sync.Mutex
	cache  map[string]cachedTxt
}

type cachedTxt struct {
	values []string
	until  time.Time
}

func (f *APITxt) Txt(ctx context.Context, id string) ([]string, error) {
	f.mu.Lock()
	if f.cache == nil {
		f.cache = map[string]cachedTxt{}
	}
	if g, ok := f.cache[id]; ok && time.Now().Before(g.until) {
		f.mu.Unlock()
		return g.values, nil
	}
	f.mu.Unlock()

	req, _ := http.NewRequestWithContext(ctx, http.MethodGet, f.URL+"/v1/dns/txt/"+id, nil)
	resp, err := f.Client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("api respondeu %d", resp.StatusCode)
	}
	var corpo struct {
		Values []string `json:"values"`
	}
	if err := json.NewDecoder(io.LimitReader(resp.Body, 4096)).Decode(&corpo); err != nil {
		return nil, err
	}
	f.mu.Lock()
	if len(f.cache) > 10000 { // não deixa a memória crescer sem fim
		f.cache = map[string]cachedTxt{}
	}
	f.cache[id] = cachedTxt{values: corpo.Values, until: time.Now().Add(5 * time.Second)}
	f.mu.Unlock()
	return corpo.Values, nil
}

type Server struct {
	Cfg  Config
	Txts TxtSource
}

// ServeDNS responde uma pergunta. Só responde pela própria zona (não é DNS aberto para a internet).
func (s *Server) ServeDNS(w dns.ResponseWriter, pedido *dns.Msg) {
	resp := new(dns.Msg)
	resp.SetReply(pedido)
	resp.Authoritative = true
	if len(pedido.Question) != 1 {
		resp.Rcode = dns.RcodeFormatError
		w.WriteMsg(resp)
		return
	}
	q := pedido.Question[0]
	nome := Parse(q.Name, s.Cfg.Zone)
	switch nome.Kind {
	case None:
		if !dns.IsSubDomain(s.Cfg.Zone, q.Name) {
			resp.Rcode = dns.RcodeRefused // pergunta de outra zone
			resp.Authoritative = false
		} else {
			resp.Rcode = dns.RcodeNameError
			resp.Ns = append(resp.Ns, s.soa())
		}
	case Apex:
		switch q.Qtype {
		case dns.TypeSOA:
			resp.Answer = append(resp.Answer, s.soa())
		case dns.TypeNS:
			for _, ns := range s.Cfg.NS {
				resp.Answer = append(resp.Answer, &dns.NS{Hdr: hdr(q.Name, dns.TypeNS, ttlZone), Ns: ns})
			}
		default:
			resp.Ns = append(resp.Ns, s.soa())
		}
	case Address:
		if q.Qtype == dns.TypeA || q.Qtype == dns.TypeANY {
			resp.Answer = append(resp.Answer, &dns.A{Hdr: hdr(q.Name, dns.TypeA, ttlAddress), A: nome.IP.AsSlice()})
		} else {
			resp.Ns = append(resp.Ns, s.soa()) // existe, mas sem esse tipo (ex.: AAAA)
		}
	case Challenge:
		if q.Qtype == dns.TypeTXT {
			ctx, cancelar := context.WithTimeout(context.Background(), 2*time.Second)
			values, err := s.Txts.Txt(ctx, nome.ID)
			cancelar()
			if err != nil {
				resp.Rcode = dns.RcodeServerFailure // o Let's Encrypt tenta de novo; melhor que dizer "não existe"
				break
			}
			for _, v := range values {
				resp.Answer = append(resp.Answer, &dns.TXT{Hdr: hdr(q.Name, dns.TypeTXT, ttlChallenge), Txt: []string{v}})
			}
		}
		if len(resp.Answer) == 0 && resp.Rcode == dns.RcodeSuccess {
			resp.Ns = append(resp.Ns, s.soa())
		}
	case IDOnly:
		resp.Ns = append(resp.Ns, s.soa())
	}
	w.WriteMsg(resp)
}

func (s *Server) soa() dns.RR {
	return &dns.SOA{
		Hdr: hdr(s.Cfg.Zone, dns.TypeSOA, ttlZone), Ns: s.Cfg.NS[0], Mbox: s.Cfg.Hostmaster,
		Serial: s.Cfg.Serial, Refresh: 3600, Retry: 600, Expire: 604800, Minttl: 60,
	}
}

func hdr(nome string, tipo uint16, ttl uint32) dns.RR_Header {
	return dns.RR_Header{Name: nome, Rrtype: tipo, Class: dns.ClassINET, Ttl: ttl}
}
