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
	Zona       string   // "lan.sofaly.tv."
	NS         []string // "ns1.sofaly.tv.", "ns2.sofaly.tv." ...
	Hostmaster string   // "hostmaster.sofaly.tv." (e-mail do SOA, com ponto no lugar do @)
	Carteiro   string   // "https://carteiro.sofaly.tv": de onde vem o TXT do desafio
	Serial     uint32
}

const (
	ttlEndereco = 86400 // o IP está no nome e nunca muda: pode guardar por 1 dia
	ttlDesafio  = 10    // o Let's Encrypt precisa ver o TXT novo logo
	ttlZona     = 3600
)

// FonteTxt busca os desafios de uma TV. Interface para o teste trocar o carteiro por um falso.
type FonteTxt interface {
	Txt(ctx context.Context, id string) ([]string, error)
}

// TxtDoCarteiro lê GET /v1/dns/txt/<id> do carteiro, com memória de 5 s (o Let's Encrypt consulta várias vezes
// e de vários lugares; isso poupa pedidos sem atrasar a validação).
type TxtDoCarteiro struct {
	URL     string
	Cliente *http.Client
	mu      sync.Mutex
	memoria map[string]txtGuardado
}

type txtGuardado struct {
	valores []string
	ate     time.Time
}

func (f *TxtDoCarteiro) Txt(ctx context.Context, id string) ([]string, error) {
	f.mu.Lock()
	if f.memoria == nil {
		f.memoria = map[string]txtGuardado{}
	}
	if g, ok := f.memoria[id]; ok && time.Now().Before(g.ate) {
		f.mu.Unlock()
		return g.valores, nil
	}
	f.mu.Unlock()

	req, _ := http.NewRequestWithContext(ctx, http.MethodGet, f.URL+"/v1/dns/txt/"+id, nil)
	resp, err := f.Cliente.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("carteiro respondeu %d", resp.StatusCode)
	}
	var corpo struct {
		Valores []string `json:"valores"`
	}
	if err := json.NewDecoder(io.LimitReader(resp.Body, 4096)).Decode(&corpo); err != nil {
		return nil, err
	}
	f.mu.Lock()
	if len(f.memoria) > 10000 { // não deixa a memória crescer sem fim
		f.memoria = map[string]txtGuardado{}
	}
	f.memoria[id] = txtGuardado{valores: corpo.Valores, ate: time.Now().Add(5 * time.Second)}
	f.mu.Unlock()
	return corpo.Valores, nil
}

type Servidor struct {
	Cfg  Config
	Txts FonteTxt
}

// ServeDNS responde uma pergunta. Só responde pela própria zona (não é DNS aberto para a internet).
func (s *Servidor) ServeDNS(w dns.ResponseWriter, pedido *dns.Msg) {
	resp := new(dns.Msg)
	resp.SetReply(pedido)
	resp.Authoritative = true
	if len(pedido.Question) != 1 {
		resp.Rcode = dns.RcodeFormatError
		w.WriteMsg(resp)
		return
	}
	q := pedido.Question[0]
	nome := Analisar(q.Name, s.Cfg.Zona)
	switch nome.Tipo {
	case Nenhum:
		if !dns.IsSubDomain(s.Cfg.Zona, q.Name) {
			resp.Rcode = dns.RcodeRefused // pergunta de outra zona
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
				resp.Answer = append(resp.Answer, &dns.NS{Hdr: cab(q.Name, dns.TypeNS, ttlZona), Ns: ns})
			}
		default:
			resp.Ns = append(resp.Ns, s.soa())
		}
	case Endereco:
		if q.Qtype == dns.TypeA || q.Qtype == dns.TypeANY {
			resp.Answer = append(resp.Answer, &dns.A{Hdr: cab(q.Name, dns.TypeA, ttlEndereco), A: nome.IP.AsSlice()})
		} else {
			resp.Ns = append(resp.Ns, s.soa()) // existe, mas sem esse tipo (ex.: AAAA)
		}
	case Desafio:
		if q.Qtype == dns.TypeTXT {
			ctx, cancelar := context.WithTimeout(context.Background(), 2*time.Second)
			valores, err := s.Txts.Txt(ctx, nome.ID)
			cancelar()
			if err != nil {
				resp.Rcode = dns.RcodeServerFailure // o Let's Encrypt tenta de novo; melhor que dizer "não existe"
				break
			}
			for _, v := range valores {
				resp.Answer = append(resp.Answer, &dns.TXT{Hdr: cab(q.Name, dns.TypeTXT, ttlDesafio), Txt: []string{v}})
			}
		}
		if len(resp.Answer) == 0 && resp.Rcode == dns.RcodeSuccess {
			resp.Ns = append(resp.Ns, s.soa())
		}
	case SoId:
		resp.Ns = append(resp.Ns, s.soa())
	}
	w.WriteMsg(resp)
}

func (s *Servidor) soa() dns.RR {
	return &dns.SOA{
		Hdr: cab(s.Cfg.Zona, dns.TypeSOA, ttlZona), Ns: s.Cfg.NS[0], Mbox: s.Cfg.Hostmaster,
		Serial: s.Cfg.Serial, Refresh: 3600, Retry: 600, Expire: 604800, Minttl: 60,
	}
}

func cab(nome string, tipo uint16, ttl uint32) dns.RR_Header {
	return dns.RR_Header{Name: nome, Rrtype: tipo, Class: dns.ClassINET, Ttl: ttl}
}
