// DNS da Sofaly para o certificado público por TV (https do painel sem aviso no navegador).
// Uso: sofaly-dns -zone lan.sofaly.tv -ns ns1.sofaly.tv,ns2.sofaly.tv -api https://api.sofaly.tv
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
	zone := flag.String("zone", "lan.sofaly.tv", "zona atendida")
	ns := flag.String("ns", "ns1.sofaly.tv,ns2.sofaly.tv", "servidores de nome da zona, separados por vírgula")
	hostmaster := flag.String("hostmaster", "hostmaster.sofaly.tv", "contato do SOA (com ponto no lugar do @)")
	api := flag.String("api", "https://api.sofaly.tv", "endereço da API")
	listen := flag.String("listen", ":53", "endereço de escuta")
	flag.Parse()

	cfg := Config{
		Zone:       dns.Fqdn(*zone),
		Hostmaster: dns.Fqdn(*hostmaster),
		API:        strings.TrimRight(*api, "/"),
		Serial:     uint32(time.Now().Unix()), // muda a cada início; a zona é calculada, não copiada
	}
	for _, n := range strings.Split(*ns, ",") {
		cfg.NS = append(cfg.NS, dns.Fqdn(strings.TrimSpace(n)))
	}
	s := &Server{Cfg: cfg, Txts: &APITxt{URL: cfg.API, Client: &http.Client{Timeout: 2 * time.Second}}}

	erros := make(chan error, 2)
	for _, rede := range []string{"udp", "tcp"} {
		srv := &dns.Server{Addr: *listen, Net: rede, Handler: s}
		go func() { erros <- srv.ListenAndServe() }()
	}
	log.Printf("sofaly-dns: zone %s em %s (udp+tcp)", cfg.Zone, *listen)
	log.Fatal(<-erros)
}
