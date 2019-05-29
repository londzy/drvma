package com.raphta.drvma.utils

import com.google.gson.GsonBuilder
import com.raphta.drvma.RequestService

import io.reactivex.disposables.CompositeDisposable
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor

import retrofit2.Retrofit
import retrofit2.adapter.rxjava2.RxJava2CallAdapterFactory
import retrofit2.converter.gson.GsonConverterFactory



object NetworkUtil {
    val BASE_URL = "base_url_for_the_analytics_backend"


    val mCompositeDisposable: CompositeDisposable? = null
    val loggingInterceptor = HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BODY)
    val client: OkHttpClient.Builder = OkHttpClient.Builder()
    val retrofit = Retrofit.Builder().addConverterFactory(GsonConverterFactory.create(GsonBuilder().create()))
            .addCallAdapterFactory(RxJava2CallAdapterFactory.create())
            .client(client.addInterceptor(loggingInterceptor).build())
            .baseUrl(BASE_URL).build()

    val analyticsApi = retrofit.create(RequestService::class.java)
}
