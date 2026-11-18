// DNS da Sofaly para o certificado público por TV (https do painel sem aviso no navegador).
// Uso: sofaly-dns -zona lan.sofaly.tv -ns ns1.sofaly.tv,ns2.sofaly.tv -carteiro https://carteiro.sofaly.tv
// Escuta UDP e TCP na porta 53. Não guarda nada em disco: pode rodar em quantas máquinas quiser.
package main

import (
	"flag"
	"log"
	"net/http"
	"strings"
	"time"

	"github.com/miekg/dns"
)

func main() {
	zona := flag.String("zona", "lan.sofaly.tv", "zona atendida")
	ns := flag.String("ns", "ns1.sofaly.tv,ns2.sofaly.tv", "servidores de nome da zona, separados por vírgula")
	hostmaster := flag.String("hostmaster", "hostmaster.sofaly.tv", "contato do SOA (com ponto no lugar do @)")
	carteiro := flag.String("carteiro", "https://carteiro.sofaly.tv", "endereço do carteiro")
	endereco := flag.String("escutar", ":53", "endereço de escuta")
	flag.Parse()

	cfg := Config{
		Zona:       dns.Fqdn(*zona),
		Hostmaster: dns.Fqdn(*hostmaster),
		Carteiro:   strings.TrimRight(*carteiro, "/"),
		Serial:     uint32(time.Now().Unix()), // muda a cada início; a zona é calculada, não copiada
	}
	for _, n := range strings.Split(*ns, ",") {
		cfg.NS = append(cfg.NS, dns.Fqdn(strings.TrimSpace(n)))
	}
	s := &Servidor{Cfg: cfg, Txts: &TxtDoCarteiro{URL: cfg.Carteiro, Cliente: &http.Client{Timeout: 2 * time.Second}}}

	erros := make(chan error, 2)
	for _, rede := range []string{"udp", "tcp"} {
		srv := &dns.Server{Addr: *endereco, Net: rede, Handler: s}
		go func() { erros <- srv.ListenAndServe() }()
	}
	log.Printf("sofaly-dns: zona %s em %s (udp+tcp)", cfg.Zona, *endereco)
	log.Fatal(<-erros)
}
