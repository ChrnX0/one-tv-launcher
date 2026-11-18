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

type fakeTxt map[string][]string

func (f fakeTxt) Txt(_ context.Context, id string) ([]string, error) {
	if id == "zzzzzzzzzzzzzzzzzzzz" {
		return nil, errors.New("api fora")
	}
	return f[id], nil
}

// Sobe o servidor de verdade (UDP, porta livre) e pergunta com um cliente DNS de verdade.
func start(t *testing.T, source TxtSource) string {
	pc, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	cfg := Config{Zone: zone, NS: []string{"ns1.sofaly.tv.", "ns2.sofaly.tv."}, Hostmaster: "hostmaster.sofaly.tv.", Serial: 1}
	srv := &dns.Server{PacketConn: pc, Handler: &Server{Cfg: cfg, Txts: source}}
	go srv.ActivateAndServe()
	t.Cleanup(func() { srv.Shutdown() })
	return pc.LocalAddr().String()
}

func ask(t *testing.T, end, nome string, tipo uint16) *dns.Msg {
	m := new(dns.Msg)
	m.SetQuestion(nome, tipo)
	r, _, err := new(dns.Client).Exchange(m, end)
	if err != nil {
		t.Fatal(err)
	}
	return r
}

func TestServer(t *testing.T) {
	end := start(t, fakeTxt{id: {"desafio-do-lets-encrypt"}})

	r := ask(t, end, "192-168-1-153."+id+"."+zone, dns.TypeA)
	if len(r.Answer) != 1 || r.Answer[0].(*dns.A).A.String() != "192.168.1.153" || !r.Authoritative {
		t.Fatalf("A: %v", r)
	}
	r = ask(t, end, "8-8-8-8."+id+"."+zone, dns.TypeA)
	if r.Rcode != dns.RcodeNameError {
		t.Fatalf("IP público devia ser NXDOMAIN: %v", r)
	}
	r = ask(t, end, "_acme-challenge."+id+"."+zone, dns.TypeTXT)
	if len(r.Answer) != 1 || r.Answer[0].(*dns.TXT).Txt[0] != "desafio-do-lets-encrypt" {
		t.Fatalf("TXT: %v", r)
	}
	r = ask(t, end, "_acme-challenge.zzzzzzzzzzzzzzzzzzzz."+zone, dns.TypeTXT)
	if r.Rcode != dns.RcodeServerFailure {
		t.Fatalf("api fora devia dar SERVFAIL: %v", r)
	}
	r = ask(t, end, zone, dns.TypeNS)
	if len(r.Answer) != 2 {
		t.Fatalf("NS: %v", r)
	}
	r = ask(t, end, "google.com.", dns.TypeA)
	if r.Rcode != dns.RcodeRefused {
		t.Fatalf("outra zona devia ser recusada: %v", r)
	}
}

// O TXT vem do api de verdade (aqui um fake por HTTP) e fica 5 s na memória.
func TestAPITxt(t *testing.T) {
	requests := 0
	fake := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requests++
		if r.URL.Path != "/v1/dns/txt/"+id {
			http.NotFound(w, r)
			return
		}
		w.Write([]byte(`{"values":["abc"]}`))
	}))
	defer fake.Close()
	f := &APITxt{URL: fake.URL, Client: fake.Client()}
	for i := 0; i < 3; i++ {
		v, err := f.Txt(context.Background(), id)
		if err != nil || len(v) != 1 || v[0] != "abc" {
			t.Fatalf("%v %v", v, err)
		}
	}
	if requests != 1 {
		t.Fatalf("esperava 1 pedido ao api (memória), houve %d", requests)
	}
}
