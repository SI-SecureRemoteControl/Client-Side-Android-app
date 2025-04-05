package ba.unsa.etf.si.secureremotecontrol.data.network


import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object RetrofitClient {

    // !!! *** UNESITE IP ADRESU VAŠEG RAČUNARA OVDJE *** !!!
    // Nemojte koristiti 'localhost' ili '127.0.0.1' sa emulatora/uređaja
    // Primjer: "http://192.168.1.10:3000/" (provjerite port iz server.js)
    private const val BASE_URL = "http://192.168.1.15:3000/"

    // Logger za mrežne zahtjeve (korisno za debug)
    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY // Logira sve detalje zahtjeva/odgovora
    }

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor) // Dodajemo logger
        .build()

    val instance: ApiService by lazy {
        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient) // Koristimo naš OkHttpClient sa loggerom
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        retrofit.create(ApiService::class.java)
    }
}