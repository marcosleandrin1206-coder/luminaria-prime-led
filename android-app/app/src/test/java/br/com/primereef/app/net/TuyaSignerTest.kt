package br.com.primereef.app.net

import org.junit.Assert.assertEquals
import org.junit.Test

/** Vetores gerados com a biblioteca de referência tinytuya (mesmas entradas, t = 1700000000000). */
class TuyaSignerTest {
    private val id = "abc123"
    private val secret = "s3cr3t"
    private val t = 1700000000000L

    @Test
    fun tokenRequest() = assertEquals(
        "FE0035154634C25E59D44C315441C3FB3987DE2EAC9B22D33D9B4616AF4C9FFC",
        TuyaSigner.sign(id, secret, null, t, "GET", "/v1.0/token?grant_type=1", null, null),
    )

    @Test
    fun getWithToken() = assertEquals(
        "CCB0DA110FFE3404E8F7B03BF2FD5D8759053B14BE9C0472A22752A09A208833",
        TuyaSigner.sign(id, secret, "tok", t, "GET", "/v1.0/devices/xyz", null, null),
    )

    @Test
    fun getWithSortedQuery() = assertEquals(
        "7028CDA9FC0506BBB50220506D306116E7B551380704A47B7D25801FFE329C3B",
        TuyaSigner.sign(id, secret, "tok", t, "GET", "/v1.0/iot-01/associated-users/devices?last_row_key=k1&size=50", null, null),
    )

    @Test
    fun postCommand() = assertEquals(
        "37B4767239748CB9F67F93CE4F2541A52C07C627193A15CAAD3725C575DC421A",
        TuyaSigner.sign(
            id, secret, "tok", t, "POST", "/v1.0/iot-03/devices/xyz/commands",
            """{"commands": [{"code": "manual_feed", "value": 2}]}""", "application/json",
        ),
    )
}
