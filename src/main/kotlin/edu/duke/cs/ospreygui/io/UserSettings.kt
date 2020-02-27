package edu.duke.cs.ospreygui.io

import edu.duke.cs.ospreyservice.read
import org.tomlj.Toml
import java.nio.file.Files
import java.nio.file.Paths


object UserSettings {

	private val file = Paths.get(System.getProperty("user.home"))
		.resolve(".osprey")
		.resolve("settings.toml")

	data class ServiceProvider(
		val hostname: String,
		val port: Int = 8080
	)

	val serviceProviders = ArrayList<ServiceProvider>()
	var serviceProvider = null as ServiceProvider?
		set(value) {
			if (value != null && value !in serviceProviders) {
				serviceProviders.add(value)
			}
			field = value
		}

	init {
		// try to load values on first use
		load()
	}

	private fun load() {

		// no file? use defaults
		if (!file.exists) {

			// set default service providers
			serviceProviders.clear()
			serviceProviders.add(
				ServiceProvider("darius.istmein.de")
			)
			serviceProvider = serviceProviders[0]

			save()

			return
		}

		// don't let errors here crash the whole program
		try {

			val doc = Toml.parse(file.read())

			// load the service providers
			serviceProviders.clear()
			val serviceProvidersArray = doc.getArray("serviceProviders")
			if (serviceProvidersArray != null) {
				for (i in 0 until serviceProvidersArray.size()) {
					val providerTable = serviceProvidersArray.getTable(i)
					if (providerTable != null) {

						serviceProviders.add(ServiceProvider(
							hostname = providerTable.getString("host") ?: continue,
							port = providerTable.getInt("port") ?: continue
						))
					}
				}
			}
			doc.getInt("serviceProvider")?.let { index ->
				serviceProvider = serviceProviders.getOrNull(index)
			}

		} catch (t: Throwable) {

			// just report the exception and move on
			// worst case, we just use default settings for this user
			t.printStackTrace(System.err)
		}
	}

	fun save() {

		val buf = StringBuilder()
		fun write(str: String, vararg args: Any) = buf.append(String.format(str, *args))

		// write the service providers
		if (serviceProviders.isNotEmpty()) {
			write("serviceProviders = [\n")
			for ((index, endpoint) in serviceProviders.withIndex()) {
				write("\t{ host = %s, port = %d }, # %d\n",
					endpoint.hostname.quote(),
					endpoint.port,
					index
				)
			}
			write("]\n")
		}
		serviceProviders
			.indexOfFirst { it === serviceProvider }
			.takeIf { it >= 0 }
			?.let {
				write("serviceProvider = %d\n", it)
			}

		Files.createDirectories(file.parent)
		buf.toString().write(file)
	}

	private fun String.quote() =
		replace("\\", "\\\\")
			.replace("\"", "\\\"")
			.let { str -> "\"$str\"" }
}
