package br.com.primereef.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class LinksTest {
    private fun urls(t: String) = Links.find(t).map { it.url }

    @Test
    fun dominio() = assertEquals(listOf("https://platform.tuya.com"), urls("Crie uma conta em platform.tuya.com (pode usar o mesmo e-mail)."))

    @Test
    fun dominioComCaminhoEPontoFinal() = assertEquals(listOf("https://platform.tuya.com/cloud"), urls("Abra platform.tuya.com/cloud."))

    @Test
    fun ip() = assertEquals(listOf("http://192.168.4.1"), urls("Abra http no 192.168.4.1, escolha o Wi-Fi"))

    @Test
    fun urlCompleta() = assertEquals(listOf("https://web.esphome.io"), urls("Use https://web.esphome.io"))

    @Test
    fun numerosComunsNaoViramLink() = assertEquals(emptyList<String>(), urls("Vazão 1.18 ml/s, KH 8.2 dKH, versão 1.3.2, 2.5 ml"))
}
