package main

import (
	"context"
	"errors"
	"net"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/miekg/dns"
)

type txtFalso map[string][]string

func (f txtFalso) Txt(_ context.Context, id string) ([]string, error) {
	if id == "zzzzzzzzzzzzzzzzzzzz" {
		return nil, errors.New("carteiro fora")
	}
	return f[id], nil
}

// Sobe o servidor de verdade (UDP, porta livre) e pergunta com um cliente DNS de verdade.
func subir(t *testing.T, fonte FonteTxt) string {
	pc, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	cfg := Config{Zona: zona, NS: []string{"ns1.sofaly.tv.", "ns2.sofaly.tv."}, Hostmaster: "hostmaster.sofaly.tv.", Serial: 1}
	srv := &dns.Server{PacketConn: pc, Handler: &Servidor{Cfg: cfg, Txts: fonte}}
	go srv.ActivateAndServe()
	t.Cleanup(func() { srv.Shutdown() })
	return pc.LocalAddr().String()
}

func perguntar(t *testing.T, end, nome string, tipo uint16) *dns.Msg {
	m := new(dns.Msg)
	m.SetQuestion(nome, tipo)
	r, _, err := new(dns.Client).Exchange(m, end)
	if err != nil {
		t.Fatal(err)
	}
	return r
}

func TestServidor(t *testing.T) {
	end := subir(t, txtFalso{id: {"desafio-do-lets-encrypt"}})

	r := perguntar(t, end, "192-168-1-153."+id+"."+zona, dns.TypeA)
	if len(r.Answer) != 1 || r.Answer[0].(*dns.A).A.String() != "192.168.1.153" || !r.Authoritative {
		t.Fatalf("A: %v", r)
	}
	r = perguntar(t, end, "8-8-8-8."+id+"."+zona, dns.TypeA)
	if r.Rcode != dns.RcodeNameError {
		t.Fatalf("IP público devia ser NXDOMAIN: %v", r)
	}
	r = perguntar(t, end, "_acme-challenge."+id+"."+zona, dns.TypeTXT)
	if len(r.Answer) != 1 || r.Answer[0].(*dns.TXT).Txt[0] != "desafio-do-lets-encrypt" {
		t.Fatalf("TXT: %v", r)
	}
	r = perguntar(t, end, "_acme-challenge.zzzzzzzzzzzzzzzzzzzz."+zona, dns.TypeTXT)
	if r.Rcode != dns.RcodeServerFailure {
		t.Fatalf("carteiro fora devia dar SERVFAIL: %v", r)
	}
	r = perguntar(t, end, zona, dns.TypeNS)
	if len(r.Answer) != 2 {
		t.Fatalf("NS: %v", r)
	}
	r = perguntar(t, end, "google.com.", dns.TypeA)
	if r.Rcode != dns.RcodeRefused {
		t.Fatalf("outra zona devia ser recusada: %v", r)
	}
}

// O TXT vem do carteiro de verdade (aqui um falso por HTTP) e fica 5 s na memória.
func TestTxtDoCarteiro(t *testing.T) {
	pedidos := 0
	falso := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		pedidos++
		if r.URL.Path != "/v1/dns/txt/"+id {
			http.NotFound(w, r)
			return
		}
		w.Write([]byte(`{"valores":["abc"]}`))
	}))
	defer falso.Close()
	f := &TxtDoCarteiro{URL: falso.URL, Cliente: falso.Client()}
	for i := 0; i < 3; i++ {
		v, err := f.Txt(context.Background(), id)
		if err != nil || len(v) != 1 || v[0] != "abc" {
			t.Fatalf("%v %v", v, err)
		}
	}
	if pedidos != 1 {
		t.Fatalf("esperava 1 pedido ao carteiro (memória), houve %d", pedidos)
	}
}
