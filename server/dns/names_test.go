package main

import "testing"

const zone = "lan.sofaly.tv."
const id = "abcdefghijklmnopqrst"

func TestParse(t *testing.T) {
	casos := []struct {
		nome string
		tipo Kind
		ip   string
	}{
		{"lan.sofaly.tv.", Apex, ""},
		{"LAN.Sofaly.TV.", Apex, ""},
		{"192-168-1-153." + id + "." + zone, Address, "192.168.1.153"},
		{"10-0-0-5." + id + "." + zone, Address, "10.0.0.5"},
		{"172-16-0-1." + id + "." + zone, Address, "172.16.0.1"},
		{"100-101-2-3." + id + "." + zone, Address, "100.101.2.3"},
		{"8-8-8-8." + id + "." + zone, None, ""},    // IP público: recusado
		{"172-32-0-1." + id + "." + zone, None, ""}, // fora de 172.16/12
		{"192-168-1-999." + id + "." + zone, None, ""},
		{"_acme-challenge." + id + "." + zone, Challenge, ""},
		{id + "." + zone, IDOnly, ""},
		{"192-168-1-1.idcurto." + zone, None, ""},
		{"a.192-168-1-1." + id + "." + zone, None, ""},
		{"sofaly.tv.", None, ""},
		{"x.outrolan.sofaly.tv.", None, ""},
	}
	for _, c := range casos {
		n := Parse(c.nome, zone)
		if n.Kind != c.tipo {
			t.Errorf("%s: tipo %v, esperava %v", c.nome, n.Kind, c.tipo)
		}
		if c.ip != "" && n.IP.String() != c.ip {
			t.Errorf("%s: ip %v, esperava %s", c.nome, n.IP, c.ip)
		}
	}
}
