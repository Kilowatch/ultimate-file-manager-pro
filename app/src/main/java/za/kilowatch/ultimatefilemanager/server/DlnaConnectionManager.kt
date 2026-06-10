package za.kilowatch.ultimatefilemanager.server

import android.util.Log
import org.w3c.dom.Element

/**
 * Minimal ConnectionManager:1 SOAP service for the DLNA Media Server.
 *
 * Handles the four required UPnP ConnectionManager actions:
 * - GetProtocolInfo
 * - GetCurrentConnectionIDs
 * - GetCurrentConnectionInfo
 * - PrepareForConnection
 *
 * Every method returns a complete SOAP 1.1 response envelope as a string,
 * using the same manual concatenation style as [DlnaXmlParser.buildDidlLite]
 * with [DlnaXmlParser.sanitizeXmlText] on all dynamic values.
 */
object DlnaConnectionManager {

    private const val TAG = "DlnaConnectionManager"

    /** The UPnP service URN for ConnectionManager:1. */
    private const val NS_CONNECTION_MANAGER = "urn:schemas-upnp-org:service:ConnectionManager:1"

    /** SOAP 1.1 envelope namespace. */
    private const val NS_SOAP_ENV = "http://schemas.xmlsoap.org/soap/envelope/"

    /** SOAP 1.1 encoding style. */
    private const val SOAP_ENCODING = "http://schemas.xmlsoap.org/soap/encoding/"

    // -----------------------------------------------------------------
    // Public entry point
    // -----------------------------------------------------------------

    /**
     * Accepts a parsed SOAP action name and the corresponding body
     * [Element], and returns a complete SOAP 1.1 response XML string.
     *
     * @param actionName The UPnP action name (e.g. "GetProtocolInfo").
     * @param soapBody   The [Element] representing the SOAP body (already
     *                   parsed by [DlnaXmlParser.parseSoapBody]).
     * @return A well-formed SOAP 1.1 response envelope.
     */
    fun handleSoapAction(actionName: String, soapBody: Element): String {
        Log.d(TAG, "Handling SOAP action: $actionName")
        return when (actionName) {
            "GetProtocolInfo" -> buildGetProtocolInfoResponse()
            "GetCurrentConnectionIDs" -> buildGetCurrentConnectionIDsResponse()
            "GetCurrentConnectionInfo" -> buildGetCurrentConnectionInfoResponse(soapBody)
            "PrepareForConnection" -> buildPrepareForConnectionResponse()
            else -> {
                Log.w(TAG, "Unknown or unsupported SOAP action: $actionName")
                buildErrorResponse(actionName, "Action not supported: $actionName")
            }
        }
    }

    // -----------------------------------------------------------------
    // SOAP response builders
    // -----------------------------------------------------------------

    /**
     * Builds a GetProtocolInfo response.
     *
     * Source advertises support for streaming video, audio and image over
     * HTTP GET.  Sink is empty because this server does not receive media.
     */
    private fun buildGetProtocolInfoResponse(): String {
        val source = DlnaXmlParser.sanitizeXmlText(
            "http-get:*:video/*:*,http-get:*:audio/*:*,http-get:*:image/*:*"
        )
        val sb = StringBuilder()
        appendEnvelopePrologue(sb)
        sb.appendLine("""  <s:Body>""")
        sb.appendLine(
            """    <u:GetProtocolInfoResponse """ +
                """xmlns:u="$NS_CONNECTION_MANAGER">"""
        )
        // Report both Source and Sink. MediaServer uses Source,
        // MediaRenderer uses Sink. BubbleUPnP expects Sink to be a
        // simple wildcard, not a complex MIME list.
        sb.appendLine("""      <Source>$source</Source>""")
        // Sink is what the renderer can receive. BubbleUPnP needs
        // at least one recognized format to list the device as usable.
        // Use the standard minimal format accepted by most clients.
        sb.appendLine("""      <Sink>http-get:*:*:*</Sink>""")
        sb.appendLine("""    </u:GetProtocolInfoResponse>""")
        sb.appendLine("""  </s:Body>""")
        sb.appendLine("""</s:Envelope>""")
        return sb.toString()
    }

    /**
     * Builds a GetCurrentConnectionIDs response.
     *
     * This simple server does not maintain active connection tracking and
     * always reports a single connection ID of "0".
     */
    private fun buildGetCurrentConnectionIDsResponse(): String {
        val sb = StringBuilder()
        appendEnvelopePrologue(sb)
        sb.appendLine("""  <s:Body>""")
        sb.appendLine(
            """    <u:GetCurrentConnectionIDsResponse """ +
                """xmlns:u="$NS_CONNECTION_MANAGER">"""
        )
        sb.appendLine("""      <ConnectionIDs>0</ConnectionIDs>""")
        sb.appendLine("""    </u:GetCurrentConnectionIDsResponse>""")
        sb.appendLine("""  </s:Body>""")
        sb.appendLine("""</s:Envelope>""")
        return sb.toString()
    }

    /**
     * Builds a GetCurrentConnectionInfo response.
     *
     * The optional ConnectionID parameter is read from the request body
     * but the response is always the same for this simple server.
     */
    private fun buildGetCurrentConnectionInfoResponse(soapBody: Element): String {
        val connectionId = extractIntParam(soapBody, "ConnectionID", 0)
        Log.d(TAG, "GetCurrentConnectionInfo for ConnectionID=$connectionId")

        val sb = StringBuilder()
        appendEnvelopePrologue(sb)
        sb.appendLine("""  <s:Body>""")
        sb.appendLine(
            """    <u:GetCurrentConnectionInfoResponse """ +
                """xmlns:u="$NS_CONNECTION_MANAGER">"""
        )
        sb.appendLine("""      <RcsID>-1</RcsID>""")
        sb.appendLine("""      <AVTransportID>-1</AVTransportID>""")
        sb.appendLine("""      <ProtocolInfo></ProtocolInfo>""")
        sb.appendLine("""      <PeerConnectionManager></PeerConnectionManager>""")
        sb.appendLine("""      <Direction>Input</Direction>""")
        sb.appendLine("""      <Status>OK</Status>""")
        sb.appendLine("""    </u:GetCurrentConnectionInfoResponse>""")
        sb.appendLine("""  </s:Body>""")
        sb.appendLine("""</s:Envelope>""")
        return sb.toString()
    }

    /**
     * Builds a PrepareForConnection response.
     *
     * This server does not support connection preparation, but UPnP
     * control points may still call this action.  A fixed response with
     * a minimal connection ID is returned.
     */
    private fun buildPrepareForConnectionResponse(): String {
        val sb = StringBuilder()
        appendEnvelopePrologue(sb)
        sb.appendLine("""  <s:Body>""")
        sb.appendLine(
            """    <u:PrepareForConnectionResponse """ +
                """xmlns:u="$NS_CONNECTION_MANAGER">"""
        )
        sb.appendLine("""      <ConnectionID>0</ConnectionID>""")
        sb.appendLine("""      <AVTransportID>-1</AVTransportID>""")
        sb.appendLine("""      <RcsID>-1</RcsID>""")
        sb.appendLine("""    </u:PrepareForConnectionResponse>""")
        sb.appendLine("""  </s:Body>""")
        sb.appendLine("""</s:Envelope>""")
        return sb.toString()
    }

    // -----------------------------------------------------------------
    // Error response
    // -----------------------------------------------------------------

    /**
     * Builds a SOAP 1.1 fault response for an unrecognised action.
     */
    private fun buildErrorResponse(actionName: String, detail: String): String {
        val sanitisedDetail = DlnaXmlParser.sanitizeXmlText(detail)
        val sanitisedAction = DlnaXmlParser.sanitizeXmlText(actionName)
        val sb = StringBuilder()
        appendEnvelopePrologue(sb)
        sb.appendLine("""  <s:Body>""")
        sb.appendLine("""    <s:Fault>""")
        sb.appendLine("""      <faultcode>s:Client</faultcode>""")
        sb.appendLine("""      <faultstring>UPnP Error</faultstring>""")
        sb.appendLine("""      <detail>""")
        sb.appendLine(
            """        <u:UPnPError xmlns:u="$NS_CONNECTION_MANAGER">"""
        )
        sb.appendLine("""          <errorCode>401</errorCode>""")
        sb.appendLine(
            """          <errorDescription>$sanitisedDetail</errorDescription>"""
        )
        sb.appendLine("""        </u:UPnPError>""")
        sb.appendLine("""      </detail>""")
        sb.appendLine("""    </s:Fault>""")
        sb.appendLine("""  </s:Body>""")
        sb.appendLine("""</s:Envelope>""")
        return sb.toString()
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    /**
     * Writes the common SOAP 1.1 envelope opening tags into [sb].
     */
    private fun appendEnvelopePrologue(sb: StringBuilder) {
        sb.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        sb.appendLine(
            """<s:Envelope """ +
                """xmlns:s="$NS_SOAP_ENV" """ +
                """s:encodingStyle="$SOAP_ENCODING">"""
        )
    }

    /**
     * Attempts to extract an integer parameter named [paramName] from the
     * SOAP body.  The [soapBody] element is expected to be the `<s:Body>`
     * element whose first child is the action-specific request element
     * (e.g. `<u:GetCurrentConnectionInfo>`).
     *
     * Returns [defaultValue] when the parameter is absent, empty, or not
     * a valid integer.
     */
    private fun extractIntParam(
        soapBody: Element,
        paramName: String,
        defaultValue: Int
    ): Int {
        // Get the action element (first child of the body).
        val actionElement = soapBody.firstChild as? Element ?: return defaultValue
        val elements = actionElement.getElementsByTagName(paramName)
        if (elements.length == 0) return defaultValue
        val textContent = elements.item(0).textContent ?: return defaultValue
        return textContent.toIntOrNull() ?: defaultValue
    }
}
