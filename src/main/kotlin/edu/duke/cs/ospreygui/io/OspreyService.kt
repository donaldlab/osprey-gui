package edu.duke.cs.ospreygui.io

import edu.duke.cs.ospreyservice.ServiceResponse
import edu.duke.cs.ospreyservice.services.*
import edu.duke.cs.ospreyservice.OspreyService as Server
import io.ktor.client.HttpClient
import io.ktor.client.call.TypeInfo
import io.ktor.client.engine.cio.CIO
import io.ktor.client.features.json.JsonFeature
import io.ktor.client.features.json.JsonSerializer
import io.ktor.client.features.json.serializer.KotlinxSerializer
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.util.KtorExperimentalAPI
import io.ktor.utils.io.core.Input
import io.ktor.utils.io.core.readText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ImplicitReflectionSerializer
import kotlinx.serialization.serializer
import kotlin.reflect.jvm.jvmErasure


object OspreyService {

	@UseExperimental(KtorExperimentalAPI::class)
	private val client =
		HttpClient(CIO) {
			install(JsonFeature) {
				serializer = ServiceSerializer()
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

	// NOTE: tragically, we can't reduce code duplication here due to a compiler bug
	// see: https://youtrack.jetbrains.com/issue/KT-34051
	// maybe someday we can?

	fun about() = runBlocking {
		client.get<ServiceResponse<AboutResponse>> {
			path("about")
			method = HttpMethod.Get
		}
		.responseOrThrow()
	}

	fun missingAtoms(request: MissingAtomsRequest) = runBlocking {
		client.get<ServiceResponse<MissingAtomsResponse>> {
			path("missingAtoms")
			method = HttpMethod.Post
			send(request)
		}
		.responseOrThrow()
	}

	fun bonds(request: BondsRequest) = runBlocking {
		client.get<ServiceResponse<BondsResponse>> {
			path("bonds")
			method = HttpMethod.Post
			send(request)
		}
		.responseOrThrow()
	}

	fun protonation(request: ProtonationRequest) = runBlocking {
		client.get<ServiceResponse<ProtonationResponse>> {
			path("protonation")
			method = HttpMethod.Post
			send(request)
		}
		.responseOrThrow()
	}

	fun protonate(request: ProtonateRequest) = runBlocking {
		client.get<ServiceResponse<ProtonateResponse>> {
			path("protonate")
			method = HttpMethod.Post
			send(request)
		}
		.responseOrThrow()
	}

	fun types(request: TypesRequest) = runBlocking {
		client.get<ServiceResponse<TypesResponse>> {
			path("types")
			method = HttpMethod.Post
			send(request)
		}
		.responseOrThrow()
	}
}


private class ServiceSerializer : JsonSerializer {

	// get ktor's usual serializer
	private val ktorSerializer = KotlinxSerializer()

	// for writes, just pass through to the usual serializer
	override fun write(data: Any, contentType: ContentType) =
		ktorSerializer.write(data, contentType)

	// for reads, look for our ServiceResponse type and handle it specially
	@UseExperimental(ImplicitReflectionSerializer::class)
	override fun read(type: TypeInfo, body: Input) =
		when (type.type) {

			ServiceResponse::class -> {

				// get the response type from the given type info
				val rtype = type.kotlinType!!.arguments[0].type!!.jvmErasure

				// de-serialize the json
				Server.json.parse(
					ServiceResponse.serializer(rtype.serializer()),
					body.readText()
				)
			}

			// otherwise, pass on to the usual serializer
			else -> ktorSerializer.read(type, body)
		}
}
