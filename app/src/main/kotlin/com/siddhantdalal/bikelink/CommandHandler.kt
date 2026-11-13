package com.siddhantdalal.bikelink

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.provider.Settings
import org.json.JSONException
import org.json.JSONObject

/**
 * Turns commands from the iPhone into actions. Every reply includes a short `message` that the Shortcut can show or
 * speak.
 *
 *     GET  /status                        Is the bike phone ready?
 *     POST /navigate   {"to": "<place>"}  Two-wheeler navigation on the car screen
 *     POST /stop                          End the ride
 *
 * Every request must carry the bike code in the X-Bike-Code header. This runs on the command server's thread,
 * so the slow parts (expanding short links, looking up place names) don't block the screen.
 */
class CommandHandler(private val context: Context, private val bikeCode: String) {

    private class NavigateCommand(val to: String, val simulate: Boolean)

    fun handle(request: HttpRequest): HttpResponse {
        if (!BikeCode.matches(bikeCode, request.header(BIKE_CODE_HEADER))) {
            return reply(401, "Wrong bike code. Check the code shown in Bike Link's setup.")
        }
        return when ("${request.method} ${request.path}") {
            "GET /status" -> reply(200, "Bike phone is ready.")
            "POST /navigate" -> navigate(request.body)
            "POST /stop" -> stop()
            else -> reply(404, "Unknown command.")
        }
    }

    private fun navigate(body: String): HttpResponse {
        val command = navigateCommand(body) ?: return reply(400, "No destination was sent.")
        if (!canOpenCarScreen()) return setupNeeded()
        if (!RouteBudget.hasRoom(context)) {
            return reply(429, "Bike Link's navigation limit is used up. It resets tomorrow, or next month at the latest.")
        }
        val destination = when (val result = DestinationParser.resolve(command.to, ShortLinkExpander::expand)) {
            is Unusable -> return reply(422, result.reason)
            is Found -> result.destination
        }
        val (latitude, longitude) = destination.coordinates()
            ?: PlaceGeocoder.locate(context, destination.query)
            ?: return reply(422, "Couldn't find \"${destination.label}\" on the map. Try adding the area or city.")
        MainActivity.navigate(context, latitude, longitude, destination.label, command.simulate)
        return reply(200, "Navigating to ${destination.label}.")
    }

    private fun stop(): HttpResponse =
        if (MainActivity.stopRide()) reply(200, "Ride stopped.") else reply(409, "There's no ride to stop.")

    /**
     * Android blocks background apps from opening a screen (Android 10+), unless the user granted "Display over other
     * apps". Without it, the car screen silently wouldn't open, so the rider is told to finish setup instead.
     */
    private fun canOpenCarScreen() =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || Settings.canDrawOverlays(context)

    private fun setupNeeded() =
        reply(409, "Finish setup on the bike phone: open Bike Link and allow \"Display over other apps\".")

    /**
     * Accepts {"to": "..."}, or the destination as plain text. {"simulate": true} rides the route in Google's
     * simulator, for testing at a desk. It's only honoured in development builds.
     */
    private fun navigateCommand(body: String): NavigateCommand? {
        val text = body.trim()
        if (!text.startsWith("{")) return text.takeIf(String::isNotBlank)?.let { NavigateCommand(it, simulate = false) }
        val json = try {
            JSONObject(text)
        } catch (e: JSONException) {
            return null
        }
        val to = json.optString("to").takeIf(String::isNotBlank) ?: return null
        val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        return NavigateCommand(to, simulate = debuggable && json.optBoolean("simulate"))
    }

    private fun reply(status: Int, message: String) =
        HttpResponse(status, JSONObject().put("ok", status == 200).put("message", message).toString())

    companion object {
        const val BIKE_CODE_HEADER = "X-Bike-Code"
    }
}
