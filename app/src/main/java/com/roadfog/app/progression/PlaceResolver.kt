package com.roadfog.app.progression

import android.content.Context
import android.location.Geocoder
import com.roadfog.app.data.PendingPlaceCandidate
import com.roadfog.app.data.PlaceDiscovery
import com.roadfog.app.data.PlaceKind
import java.io.IOException
import java.util.Locale

object PlaceResolver {
    @Suppress("DEPRECATION")
    fun resolve(context: Context, candidate: PendingPlaceCandidate): List<PlaceDiscovery>? {
        if (!Geocoder.isPresent()) return null
        val address = try {
            Geocoder(context.applicationContext, Locale.US)
                .getFromLocation(candidate.latitude, candidate.longitude, 1)
                ?.firstOrNull()
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } ?: return null

        val countryName = address.countryName?.trim().orEmpty()
        val countryKey = canonical(address.countryCode?.takeIf { it.isNotBlank() } ?: countryName)
        val stateName = address.adminArea?.trim().orEmpty()
        val townName = address.locality?.trim().orEmpty()
        val result = ArrayList<PlaceDiscovery>(3)

        if (countryKey.isNotBlank() && countryName.isNotBlank()) {
            result += discovery(candidate, PlaceKind.COUNTRY, countryKey, countryName, "", countryName)
        }
        if (stateName.isNotBlank()) {
            val stateKey = listOf(countryKey, canonical(stateName)).filter { it.isNotBlank() }.joinToString("|")
            result += discovery(candidate, PlaceKind.STATE, stateKey, stateName, countryName, countryName)
        }
        if (townName.isNotBlank()) {
            val townKey = listOf(countryKey, canonical(stateName), canonical(townName))
                .filter { it.isNotBlank() }.joinToString("|")
            result += discovery(candidate, PlaceKind.TOWN, townKey, townName, stateName, countryName)
        }
        return result
    }

    private fun discovery(
        candidate: PendingPlaceCandidate,
        kind: PlaceKind,
        key: String,
        name: String,
        parent: String,
        country: String
    ) = PlaceDiscovery(
        kind = kind,
        key = key,
        displayName = name,
        parentName = parent,
        countryName = country,
        visitedAt = candidate.visitedAt,
        latitude = candidate.latitude,
        longitude = candidate.longitude
    )

    private fun canonical(value: String): String =
        value.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
}
