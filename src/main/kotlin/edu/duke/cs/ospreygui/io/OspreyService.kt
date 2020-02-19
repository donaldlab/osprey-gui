package edu.duke.cs.ospreygui.io

import edu.duke.cs.ospreyservice.HelloRequest
import edu.duke.cs.ospreyservice.HelloResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.features.json.JsonFeature
import io.ktor.client.features.json.serializer.KotlinxSerializer
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking


object OspreyService {

	private val client =
		HttpClient(CIO) {
			install(JsonFeature) {
				serializer = KotlinxSerializer()
			}
		}

	// TODO: make configurable
	private var host = "localhost"
	private var port = 8080

	private fun HttpRequestBuilder.path(path: String) {
		url {
			host = this@OspreyService.host
			port = this@OspreyService.port
			path(path)
		}
	}

	private fun HttpRequestBuilder.send(obj: Any) {
		contentType(ContentType.Application.Json)
		body = obj
	}

	fun hello(name: String) = runBlocking {
		client.get<HelloResponse> {
			path("/")
			send(HelloRequest(name))
		}
	}
}

fun main() = edu.duke.cs.ospreyservice.OspreyService.use {

	println(OspreyService.hello("Jeff"))
}
