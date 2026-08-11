package com.oojoo.farm.master.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerEndpointTest {
    @Test
    fun `normalizes supported root endpoints`() {
        assertEquals(
            ServerEndpointValidation.Valid("http://10.0.2.2:4000/"),
            validateServerEndpoint("  HTTP://10.0.2.2:4000  "),
        )
        assertEquals(
            ServerEndpointValidation.Valid("https://farm.example.com/"),
            validateServerEndpoint("https://FARM.EXAMPLE.COM/"),
        )
    }

    @Test
    fun `rejects unsafe or non-root endpoints`() {
        val rejected = listOf(
            "",
            "farm.example.com",
            "ftp://farm.example.com",
            "http://user:secret@farm.example.com",
            "http://farm.example.com/api",
            "http://farm.example.com/?token=secret",
            "http://farm.example.com/#fragment",
            "http://farm.example.com:70000",
        )

        rejected.forEach { input ->
            assertTrue("Expected invalid endpoint: $input", validateServerEndpoint(input) is ServerEndpointValidation.Invalid)
        }
    }
}
