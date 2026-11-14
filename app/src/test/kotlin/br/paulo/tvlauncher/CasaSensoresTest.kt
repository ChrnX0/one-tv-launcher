package br.paulo.tvlauncher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** Ideia 51: sensores e ar-condicionado na tela Casa. */
class CasaSensoresTest {
    private val estados = """[
      {"entity_id":"sensor.sala_temp","state":"23.456","attributes":{"friendly_name":"Temperatura","device_class":"temperature","unit_of_measurement":"°C"}},
      {"entity_id":"sensor.sala_umid","state":"55","attributes":{"friendly_name":"Umidade","device_class":"humidity","unit_of_measurement":"%"}},
      {"entity_id":"sensor.energia","state":"120","attributes":{"device_class":"power","unit_of_measurement":"W"}},
      {"entity_id":"binary_sensor.porta","state":"on","attributes":{"friendly_name":"Porta","device_class":"door"}},
      {"entity_id":"binary_sensor.bateria","state":"off","attributes":{"device_class":"battery"}},
      {"entity_id":"camera.quintal","state":"idle","attributes":{"friendly_name":"Quintal"}},
      {"entity_id":"lock.frente","state":"locked","attributes":{}},
      {"entity_id":"climate.ar","state":"cool","attributes":{"friendly_name":"Ar","temperature":22,"current_temperature":26.5,"min_temp":17,"max_temp":30,"target_temp_step":0.5}}
    ]"""

    @Test fun soAsClassesEscolhidas() {
        val l = CasaHA.lerEstados(estados)
        assertEquals(setOf("sensor.sala_temp", "sensor.sala_umid", "binary_sensor.porta", "climate.ar", "camera.quintal"), l.map { it.id }.toSet())
        val porta = l.first { it.id == "binary_sensor.porta" }
        assertTrue(porta.somenteLeitura); assertEquals("door", porta.classe)
        val ar = l.first { it.id == "climate.ar" }
        assertFalse(ar.somenteLeitura)
        assertEquals(22.0, ar.clima!!.alvo!!, 0.0); assertEquals(0.5, ar.clima!!.passo, 0.0)
        assertEquals(22.0, ar.json().getDouble("alvo"), 0.0)
    }

    @Test fun valorDoSensor() {
        assertEquals("23,5 °C", CasaHA.valorSensor("23.456", "°C", Locale("pt", "BR")))
        assertEquals("23.5 °C", CasaHA.valorSensor("23.456", "°C", Locale.US))
        assertEquals("55%", CasaHA.valorSensor("55", "%", Locale.US))
        assertNull(CasaHA.valorSensor("unavailable", "°C", Locale.US))
        assertNull(CasaHA.valorSensor("NaN", "°C", Locale.US))
    }

    @Test fun temperaturaComPassoEFaixa() {
        val c = CasaHA.lerClima(JSONObject("""{"min_temp":17,"max_temp":30,"target_temp_step":0.5}"""))
        assertEquals(22.5, CasaHA.proximaTemp(22.0, +1, c), 0.0)
        assertEquals(17.0, CasaHA.proximaTemp(17.0, -1, c), 0.0)
        assertEquals(30.0, CasaHA.proximaTemp(30.0, +1, c), 0.0)
        val padrao = CasaHA.lerClima(null)
        assertEquals(25.0, CasaHA.proximaTemp(null, +1, padrao), 0.0)
        assertEquals(1.0, CasaHA.lerClima(JSONObject("""{"target_temp_step":99}""")).passo, 0.0)
    }

    @Test fun acoesDoArESensores() {
        assertEquals("climate" to "turn_on", CasaHA.servico("climate.ar", "ligar"))
        assertEquals("climate" to "turn_off", CasaHA.servico("climate.ar", "desligar"))
        assertNull(CasaHA.servico("climate.ar", "alternar"))
        assertNull(CasaHA.servico("sensor.sala_temp", "ligar"))
        assertNull(CasaHA.servico("binary_sensor.porta", "alternar"))
        assertNull(CasaHA.servico("lock.frente", "desligar"))
        assertTrue(CasaHA.ligado("climate", "cool")); assertFalse(CasaHA.ligado("climate", "off"))
        assertFalse(CasaHA.ligado("sensor", "23"))
        assertEquals("off", CasaHA.inverso("climate", "heat")); assertNull(CasaHA.inverso("climate", "off"))
        assertNull(CasaHA.inverso("binary_sensor", "on"))
        assertEquals("ar", CasaHA.iconeDe("climate"))
        assertEquals("camera", CasaHA.iconeDe("camera"))   // ideia 52
        assertFalse(CasaHA.ligado("camera", "idle")); assertNull(CasaHA.inverso("camera", "idle"))
        assertNull("câmera não passa pelo catálogo de serviços", CasaHA.servico("camera.quintal", "ligar"))
    }
}
