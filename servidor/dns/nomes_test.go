package main

import "testing"

const zona = "lan.sofaly.tv."
const id = "abcdefghijklmnopqrst"

func TestAnalisar(t *testing.T) {
	casos := []struct {
		nome string
		tipo Tipo
		ip   string
	}{
		{"lan.sofaly.tv.", Apex, ""},
		{"LAN.Sofaly.TV.", Apex, ""},
		{"192-168-1-153." + id + "." + zona, Endereco, "192.168.1.153"},
		{"10-0-0-5." + id + "." + zona, Endereco, "10.0.0.5"},
		{"172-16-0-1." + id + "." + zona, Endereco, "172.16.0.1"},
		{"100-101-2-3." + id + "." + zona, Endereco, "100.101.2.3"},
		{"8-8-8-8." + id + "." + zona, Nenhum, ""},    // IP público: recusado
		{"172-32-0-1." + id + "." + zona, Nenhum, ""}, // fora de 172.16/12
		{"192-168-1-999." + id + "." + zona, Nenhum, ""},
		{"_acme-challenge." + id + "." + zona, Desafio, ""},
		{id + "." + zona, SoId, ""},
		{"192-168-1-1.idcurto." + zona, Nenhum, ""},
		{"a.192-168-1-1." + id + "." + zona, Nenhum, ""},
		{"sofaly.tv.", Nenhum, ""},
		{"x.outrolan.sofaly.tv.", Nenhum, ""},
	}
	for _, c := range casos {
		n := Analisar(c.nome, zona)
		if n.Tipo != c.tipo {
			t.Errorf("%s: tipo %v, esperava %v", c.nome, n.Tipo, c.tipo)
		}
		if c.ip != "" && n.IP.String() != c.ip {
			t.Errorf("%s: ip %v, esperava %s", c.nome, n.IP, c.ip)
		}
	}
}
