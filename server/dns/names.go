// Lógica pura dos nomes do certificado por TV (sem rede), testada em names_test.go.
//
// Cada TV tem um id de 20 letras (o mesmo do api) e um certificado *.<id>.lan.sofaly.tv.
// O celular abre https://192-168-1-153.<id>.lan.sofaly.tv:8444 e este DNS responde 192.168.1.153: o IP está
// escrito no próprio nome, então não guardamos nada (por isso dá para ter quantas máquinas quiser, todas iguais).
package main

import (
	"net/netip"
	"regexp"
	"strings"
)

var idRe = regexp.MustCompile(`^[a-z2-7]{20}$`)

// Só redes de casa: 10/8, 172.16/12, 192.168/16 (RFC 1918) e 100.64/10 (Tailscale e CGNAT).
// IP público no nome = não existe. Assim ninguém usa sofaly.tv para apontar para um site qualquer.
var localNets = []netip.Prefix{
	netip.MustParsePrefix("10.0.0.0/8"),
	netip.MustParsePrefix("172.16.0.0/12"),
	netip.MustParsePrefix("192.168.0.0/16"),
	netip.MustParsePrefix("100.64.0.0/10"),
}

type Kind int

const (
	None      Kind = iota // nome que não é nosso ou não existe
	Apex                  // a própria zona (SOA, NS)
	Address               // 192-168-1-153.<id>.zone → IP
	Challenge             // _acme-challenge.<id>.zone → TXT do Let's Encrypt
	IDOnly                // <id>.zone: existe (para o certificado), sem endereço
)

type Name struct {
	Kind Kind
	ID   string
	IP   netip.Addr
}

// Parse recebe o nome perguntado e a zona, ambos com ponto final (forma do DNS), e diz o que ele é.
func Parse(pergunta, zone string) Name {
	p := strings.ToLower(pergunta)
	z := strings.ToLower(zone)
	if p == z {
		return Name{Kind: Apex}
	}
	if !strings.HasSuffix(p, "."+z) {
		return Name{}
	}
	partes := strings.Split(strings.TrimSuffix(p, "."+z), ".")
	switch len(partes) {
	case 1:
		if idRe.MatchString(partes[0]) {
			return Name{Kind: IDOnly, ID: partes[0]}
		}
	case 2:
		id := partes[1]
		if !idRe.MatchString(id) {
			return Name{}
		}
		if partes[0] == "_acme-challenge" {
			return Name{Kind: Challenge, ID: id}
		}
		if ip, ok := ipFromLabel(partes[0]); ok {
			return Name{Kind: Address, ID: id, IP: ip}
		}
	}
	return Name{}
}

// "192-168-1-153" → 192.168.1.153, só se for de rede local.
func ipFromLabel(rotulo string) (netip.Addr, bool) {
	ip, err := netip.ParseAddr(strings.ReplaceAll(rotulo, "-", "."))
	if err != nil || !ip.Is4() {
		return netip.Addr{}, false
	}
	for _, rede := range localNets {
		if rede.Contains(ip) {
			return ip, true
		}
	}
	return netip.Addr{}, false
}
