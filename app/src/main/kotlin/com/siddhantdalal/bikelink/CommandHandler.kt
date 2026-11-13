package com.siddhantdalal.bikelink

import android.content.Context
import org.json.JSONException
import org.json.JSONObject

/**
 * Turns commands from the iPhone into actions. Every reply includes a short `message` that the Shortcut can show or
 * speak.
 *
 *     GET  /status                        Is the bike phone ready?
 *     POST /navigate   {"to": "<place>"}  Two-wheeler navigation in Google Maps
 *     POST /stop                          End navigation (needs the Maps helper)
 *
 * Every request must carry the bike code in the X-Bike-Code header.
 */
class CommandHandler(private val context: Context, private val bikeCode: String) {

    fun handle(request: HttpRequest): HttpResponse {
        if (!BikeCode.matches(bikeCode, request.header(BIKE_CODE_HEADER))) {
            return reply(401, "Wrong bike code. Check the code shown in the Bike Link app.")
        }
        return when ("${request.method} ${request.path}") {
            "GET /status" -> reply(200, "Bike phone is ready.")
            "POST /navigate" -> navigate(request.body)
            "POST /stop" -> stop()
            else -> reply(404, "Unknown command.")
        }
    }

    private fun navigate(body: String): HttpResponse {
        val input = destinationText(body) ?: return reply(400, "No destination was sent.")
        if (!GoogleMaps.isInstalled(context)) return reply(409, "Google Maps isn't installed on the bike phone.")
        return when (val result = DestinationParser.resolve(input, ShortLinkExpander::expand)) {
            is Unusable -> reply(422, result.reason)
            is Found -> {
                // If Maps is already navigating, it will ask "Exit navigation?". The helper answers it, if turned on.
                MapsHelperService.request(MapsHelperService.Task.CONFIRM_NEW_ROUTE)
                LaunchMapsActivity.navigate(context, result.destination.query)
                reply(200, "Navigating to ${result.destination.label}.")
            }
        }
    }

    private fun stop(): HttpResponse {
        if (!MapsHelperService.isEnabled) {
            return reply(409, "Turn on the Maps helper in Bike Link to stop rides from your iPhone.")
        }
        MapsHelperService.request(MapsHelperService.Task.STOP_NAVIGATION)
        LaunchMapsActivity.showMaps(context)
        return reply(200, "Stopping the ride.")
    }

    /** Accepts {"to": "..."}, or the destination as plain text. */
    private fun destinationText(body: String): String? {
        val text = body.trim()
        val destination = if (text.startsWith("{")) {
            try {
                JSONObject(text).optString("to")
            } catch (e: JSONException) {
                null
            }
        } else {
            text
        }
        return destination?.takeIf(String::isNotBlank)
    }

    private fun reply(status: Int, message: String) =
        HttpResponse(status, JSONObject().put("ok", status == 200).put("message", message).toString())

    companion object {
        const val BIKE_CODE_HEADER = "X-Bike-Code"
    }
}
