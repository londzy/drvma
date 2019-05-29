package com.raphta.drvma

import com.raphta.drvma.model.data.KnownDriver
import io.reactivex.Observable

import retrofit2.http.GET



// Request service with methods for sending data to the backend

interface RequestService {

    @GET("api/known_drivers")
    fun getKnownDrivers(): Observable<List<KnownDriver>>


}


