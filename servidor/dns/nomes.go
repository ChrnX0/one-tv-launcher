// Lógica pura dos nomes do certificado por TV (sem rede), testada em nomes_test.go.
//
// Cada TV tem um id de 20 letras (o mesmo do carteiro) e um certificado *.<id>.lan.sofaly.tv.
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
var redesLocais = []netip.Prefix{
	netip.MustParsePrefix("10.0.0.0/8"),
	netip.MustParsePrefix("172.16.0.0/12"),
	netip.MustParsePrefix("192.168.0.0/16"),
	netip.MustParsePrefix("100.64.0.0/10"),
}

type Tipo int

const (
	Nenhum   Tipo = iota // nome que não é nosso ou não existe
	Apex                 // a própria zona (SOA, NS)
	Endereco             // 192-168-1-153.<id>.zona → IP
	Desafio              // _acme-challenge.<id>.zona → TXT do Let's Encrypt
	SoId                 // <id>.zona: existe (para o certificado), sem endereço
)

type Nome struct {
	Tipo Tipo
	ID   string
	IP   netip.Addr
}

// Analisar recebe o nome perguntado e a zona, ambos com ponto final (forma do DNS), e diz o que ele é.
func Analisar(pergunta, zona string) Nome {
	p := strings.ToLower(pergunta)
	z := strings.ToLower(zona)
	if p == z {
		return Nome{Tipo: Apex}
	}
	if !strings.HasSuffix(p, "."+z) {
		return Nome{}
	}
	partes := strings.Split(strings.TrimSuffix(p, "."+z), ".")
	switch len(partes) {
	case 1:
		if idRe.MatchString(partes[0]) {
			return Nome{Tipo: SoId, ID: partes[0]}
		}
	case 2:
		id := partes[1]
		if !idRe.MatchString(id) {
			return Nome{}
		}
		if partes[0] == "_acme-challenge" {
			return Nome{Tipo: Desafio, ID: id}
		}
		if ip, ok := ipDoRotulo(partes[0]); ok {
			return Nome{Tipo: Endereco, ID: id, IP: ip}
		}
	}
	return Nome{}
}

// "192-168-1-153" → 192.168.1.153, só se for de rede local.
func ipDoRotulo(rotulo string) (netip.Addr, bool) {
	ip, err := netip.ParseAddr(strings.ReplaceAll(rotulo, "-", "."))
	if err != nil || !ip.Is4() {
		return netip.Addr{}, false
	}
	for _, rede := range redesLocais {
		if rede.Contains(ip) {
			return ip, true
		}
	}
	return netip.Addr{}, false
}
