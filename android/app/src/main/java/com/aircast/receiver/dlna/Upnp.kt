package com.aircast.receiver.dlna

/**
 * Static UPnP/DLNA documents.
 *
 * A DLNA controller (Android's gallery "Cast", VLC, BubbleUPnP, Windows "Cast to device",
 * Kodi, Plex …) fetches `/description.xml`, then the SCPD of each service, before it will
 * show the renderer at all — so these documents have to be complete and well-formed even
 * though nothing in the app itself reads them.
 */
object Upnp {

    const val DEVICE_TYPE = "urn:schemas-upnp-org:device:MediaRenderer:1"
    const val SVC_AVTRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
    const val SVC_RENDERING = "urn:schemas-upnp-org:service:RenderingControl:1"
    const val SVC_CONNECTION = "urn:schemas-upnp-org:service:ConnectionManager:1"

    /** Everything the renderer claims it can play. Controllers filter their UI on this. */
    val PROTOCOL_INFO_SINK: String = listOf(
        "http-get:*:video/mp4:*",
        "http-get:*:video/x-matroska:*",
        "http-get:*:video/webm:*",
        "http-get:*:video/3gpp:*",
        "http-get:*:video/quicktime:*",
        "http-get:*:video/x-msvideo:*",
        "http-get:*:video/avi:*",
        "http-get:*:video/mpeg:*",
        "http-get:*:video/x-flv:*",
        "http-get:*:video/MP2T:*",
        "http-get:*:video/vnd.dlna.mpeg-tts:*",
        "http-get:*:application/x-mpegURL:*",
        "http-get:*:application/vnd.apple.mpegurl:*",
        "http-get:*:application/dash+xml:*",
        "http-get:*:video/*:*",
        "http-get:*:audio/mpeg:*",
        "http-get:*:audio/mp4:*",
        "http-get:*:audio/aac:*",
        "http-get:*:audio/x-wav:*",
        "http-get:*:audio/wav:*",
        "http-get:*:audio/flac:*",
        "http-get:*:audio/x-flac:*",
        "http-get:*:audio/ogg:*",
        "http-get:*:audio/x-ms-wma:*",
        "http-get:*:audio/*:*",
        "http-get:*:image/jpeg:*",
        "http-get:*:image/png:*",
        "http-get:*:image/gif:*",
        "http-get:*:image/webp:*",
        "http-get:*:image/bmp:*",
        "http-get:*:image/*:*",
    ).joinToString(",")

    fun description(
        friendlyName: String,
        udn: String,
        baseUrl: String,
        serial: String,
        projectUrl: String,
    ): String = """<?xml version="1.0" encoding="utf-8"?>
<root xmlns="urn:schemas-upnp-org:device-1-0" xmlns:dlna="urn:schemas-dlna-org:device-1-0" xmlns:sec="http://www.sec.co.kr/dlna">
  <specVersion>
    <major>1</major>
    <minor>0</minor>
  </specVersion>
  <device>
    <dlna:X_DLNADOC xmlns:dlna="urn:schemas-dlna-org:device-1-0">DMR-1.50</dlna:X_DLNADOC>
    <deviceType>$DEVICE_TYPE</deviceType>
    <friendlyName>${escape(friendlyName)}</friendlyName>
    <manufacturer>AirCast</manufacturer>
    <manufacturerURL>${escape(projectUrl)}</manufacturerURL>
    <modelDescription>AirCast Media Renderer</modelDescription>
    <modelName>AirCast</modelName>
    <modelNumber>1.0.0</modelNumber>
    <modelURL>${escape(projectUrl)}</modelURL>
    <serialNumber>${escape(serial)}</serialNumber>
    <UDN>uuid:$udn</UDN>
    <presentationURL>$baseUrl/</presentationURL>
    <iconList>
      <icon>
        <mimetype>image/png</mimetype>
        <width>48</width><height>48</height><depth>24</depth>
        <url>/icon/48.png</url>
      </icon>
      <icon>
        <mimetype>image/png</mimetype>
        <width>120</width><height>120</height><depth>24</depth>
        <url>/icon/120.png</url>
      </icon>
      <icon>
        <mimetype>image/png</mimetype>
        <width>512</width><height>512</height><depth>24</depth>
        <url>/icon/512.png</url>
      </icon>
    </iconList>
    <serviceList>
      <service>
        <serviceType>$SVC_AVTRANSPORT</serviceType>
        <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
        <SCPDURL>/scpd/AVTransport.xml</SCPDURL>
        <controlURL>/control/AVTransport</controlURL>
        <eventSubURL>/event/AVTransport</eventSubURL>
      </service>
      <service>
        <serviceType>$SVC_RENDERING</serviceType>
        <serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>
        <SCPDURL>/scpd/RenderingControl.xml</SCPDURL>
        <controlURL>/control/RenderingControl</controlURL>
        <eventSubURL>/event/RenderingControl</eventSubURL>
      </service>
      <service>
        <serviceType>$SVC_CONNECTION</serviceType>
        <serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId>
        <SCPDURL>/scpd/ConnectionManager.xml</SCPDURL>
        <controlURL>/control/ConnectionManager</controlURL>
        <eventSubURL>/event/ConnectionManager</eventSubURL>
      </service>
    </serviceList>
  </device>
</root>
"""

    val AV_TRANSPORT_SCPD = """<?xml version="1.0" encoding="utf-8"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <actionList>
    ${action("SetAVTransportURI", arg("InstanceID", "in", "A_ARG_TYPE_InstanceID") + arg("CurrentURI", "in", "AVTransportURI") + arg("CurrentURIMetaData", "in", "AVTransportURIMetaData"))}
    ${action("SetNextAVTransportURI", arg("InstanceID", "in", "A_ARG_TYPE_InstanceID") + arg("NextURI", "in", "NextAVTransportURI") + arg("NextURIMetaData", "in", "NextAVTransportURIMetaData"))}
    ${action("GetMediaInfo", arg("InstanceID", "in", "A_ARG_TYPE_InstanceID") + arg("NrTracks", "out", "NumberOfTracks") + arg("MediaDuration", "out", "CurrentMediaDuration") + arg("CurrentURI", "out", "AVTransportURI") + arg("CurrentURIMetaData", "out", "AVTransportURIMetaData") + arg("NextURI", "out", "NextAVTransportURI") + arg("NextURIMetaData", "out", "NextAVTransportURIMetaData") + arg("PlayMedium", "out", "PlaybackStorageMedium") + arg("RecordMedium", "out", "RecordStorageMedium") + arg("WriteStatus", "out", "RecordMediumWriteStatus"))}
    ${action("GetTransportInfo", arg("InstanceID", "in", "A_ARG_TYPE_InstanceID") + arg("CurrentTransportState", "out", "TransportState") + arg("CurrentTransportStatus", "out", "TransportStatus") + arg("CurrentSpeed", "out", "TransportPlaySpeed"))}
    ${action("GetPositionInfo", arg("InstanceID", "in", "A_ARG_TYPE_InstanceID") + arg("Track", "out", "CurrentTrack") + arg("TrackDuration", "out", "CurrentTrackDuration") + arg("TrackMetaData", "out", "CurrentTrackMetaData") + arg("TrackURI", "out", "CurrentTrackURI") + arg("RelTime", "out", "RelativeTimePosition") + arg("AbsTime", "out", "AbsoluteTimePosition") + arg("RelCount", "out", "RelativeCounterPosition") + arg("AbsCount", "out", "AbsoluteCounterPosition"))}
    ${action("GetDeviceCapabilities", arg("InstanceID", "in", "A_ARG_TYPE_InstanceID") + arg("PlayMedia", "out", "PossiblePlaybackStorageMedia") + arg("RecMedia", "out", "PossibleRecordStorageMedia") + arg("RecQualityModes", "out", "PossibleRecordQualityModes"))}
    ${action("GetTransportSettings", arg("InstanceID", "in", "A_ARG_TYPE_InstanceID") + arg("PlayMode", "out", "CurrentPlayMode") + arg("RecQualityMode", "out", "CurrentRecordQualityMode"))}
    ${action("Stop", arg("InstanceID", "in", "A_ARG_TYPE_InstanceID"))}
    ${action("Play", arg("InstanceID", "in", "A_ARG_TYPE_InstanceID") + arg("Speed", "in", "TransportPlaySpeed"))}
    ${action("Pause", arg("InstanceID", "in", "A_ARG_TYPE_InstanceID"))}
    ${action("Seek", arg("InstanceID", "in", "A_ARG_TYPE_InstanceID") + arg("Unit", "in", "A_ARG_TYPE_SeekMode") + arg("Target", "in", "A_ARG_TYPE_SeekTarget"))}
    ${action("Next", arg("InstanceID", "in", "A_ARG_TYPE_InstanceID"))}
    ${action("Previous", arg("InstanceID", "in", "A_ARG_TYPE_InstanceID"))}
    ${action("SetPlayMode", arg("InstanceID", "in", "A_ARG_TYPE_InstanceID") + arg("NewPlayMode", "in", "CurrentPlayMode"))}
    ${action("GetCurrentTransportActions", arg("InstanceID", "in", "A_ARG_TYPE_InstanceID") + arg("Actions", "out", "CurrentTransportActions"))}
  </actionList>
  <serviceStateTable>
    ${stateVar("TransportState", "string", true, listOf("STOPPED", "PLAYING", "PAUSED_PLAYBACK", "TRANSITIONING", "NO_MEDIA_PRESENT"))}
    ${stateVar("TransportStatus", "string", true, listOf("OK", "ERROR_OCCURRED"))}
    ${stateVar("PlaybackStorageMedium", "string", true)}
    ${stateVar("RecordStorageMedium", "string", true)}
    ${stateVar("PossiblePlaybackStorageMedia", "string", true)}
    ${stateVar("PossibleRecordStorageMedia", "string", true)}
    ${stateVar("CurrentPlayMode", "string", true, listOf("NORMAL", "REPEAT_ONE", "REPEAT_ALL", "SHUFFLE"))}
    ${stateVar("TransportPlaySpeed", "string", true)}
    ${stateVar("RecordMediumWriteStatus", "string", true)}
    ${stateVar("CurrentRecordQualityMode", "string", true)}
    ${stateVar("PossibleRecordQualityModes", "string", true)}
    ${stateVar("NumberOfTracks", "ui4", true)}
    ${stateVar("CurrentTrack", "ui4", true)}
    ${stateVar("CurrentTrackDuration", "string", true)}
    ${stateVar("CurrentMediaDuration", "string", true)}
    ${stateVar("CurrentTrackMetaData", "string", true)}
    ${stateVar("CurrentTrackURI", "string", true)}
    ${stateVar("AVTransportURI", "string", true)}
    ${stateVar("AVTransportURIMetaData", "string", true)}
    ${stateVar("NextAVTransportURI", "string", true)}
    ${stateVar("NextAVTransportURIMetaData", "string", true)}
    ${stateVar("RelativeTimePosition", "string", false)}
    ${stateVar("AbsoluteTimePosition", "string", false)}
    ${stateVar("RelativeCounterPosition", "i4", false)}
    ${stateVar("AbsoluteCounterPosition", "i4", false)}
    ${stateVar("CurrentTransportActions", "string", false)}
    ${stateVar("LastChange", "string", true)}
    ${stateVar("A_ARG_TYPE_SeekMode", "string", false, listOf("TRACK_NR", "REL_TIME", "ABS_TIME"))}
    ${stateVar("A_ARG_TYPE_SeekTarget", "string", false)}
    ${stateVar("A_ARG_TYPE_InstanceID", "ui4", false)}
  </serviceStateTable>
</scpd>
"""

    val RENDERING_CONTROL_SCPD = """<?xml version="1.0" encoding="utf-8"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <actionList>
    ${action("ListPresets", arg("InstanceID", "in", "A_ARG_TYPE_InstanceID") + arg("CurrentPresetNameList", "out", "PresetNameList"))}
    ${action("SelectPreset", arg("InstanceID", "in", "A_ARG_TYPE_InstanceID") + arg("PresetName", "in", "A_ARG_TYPE_PresetName"))}
    ${action("GetVolume", arg("InstanceID", "in", "A_ARG_TYPE_InstanceID") + arg("Channel", "in", "A_ARG_TYPE_Channel") + arg("CurrentVolume", "out", "Volume"))}
    ${action("SetVolume", arg("InstanceID", "in", "A_ARG_TYPE_InstanceID") + arg("Channel", "in", "A_ARG_TYPE_Channel") + arg("DesiredVolume", "in", "Volume"))}
    ${action("GetMute", arg("InstanceID", "in", "A_ARG_TYPE_InstanceID") + arg("Channel", "in", "A_ARG_TYPE_Channel") + arg("CurrentMute", "out", "Mute"))}
    ${action("SetMute", arg("InstanceID", "in", "A_ARG_TYPE_InstanceID") + arg("Channel", "in", "A_ARG_TYPE_Channel") + arg("DesiredMute", "in", "Mute"))}
  </actionList>
  <serviceStateTable>
    ${stateVar("PresetNameList", "string", false)}
    ${stateVar("LastChange", "string", true)}
    <stateVariable sendEvents="no">
      <name>Volume</name><dataType>ui2</dataType>
      <allowedValueRange><minimum>0</minimum><maximum>100</maximum><step>1</step></allowedValueRange>
    </stateVariable>
    ${stateVar("Mute", "boolean", false)}
    ${stateVar("A_ARG_TYPE_Channel", "string", false, listOf("Master"))}
    ${stateVar("A_ARG_TYPE_PresetName", "string", false, listOf("FactoryDefaults"))}
    ${stateVar("A_ARG_TYPE_InstanceID", "ui4", false)}
  </serviceStateTable>
</scpd>
"""

    val CONNECTION_MANAGER_SCPD = """<?xml version="1.0" encoding="utf-8"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <actionList>
    ${action("GetProtocolInfo", arg("Source", "out", "SourceProtocolInfo") + arg("Sink", "out", "SinkProtocolInfo"))}
    ${action("GetCurrentConnectionIDs", arg("ConnectionIDs", "out", "CurrentConnectionIDs"))}
    ${action("GetCurrentConnectionInfo", arg("ConnectionID", "in", "A_ARG_TYPE_ConnectionID") + arg("RcsID", "out", "A_ARG_TYPE_RcsID") + arg("AVTransportID", "out", "A_ARG_TYPE_AVTransportID") + arg("ProtocolInfo", "out", "A_ARG_TYPE_ProtocolInfo") + arg("PeerConnectionManager", "out", "A_ARG_TYPE_ConnectionManager") + arg("PeerConnectionID", "out", "A_ARG_TYPE_ConnectionID") + arg("Direction", "out", "A_ARG_TYPE_Direction") + arg("Status", "out", "A_ARG_TYPE_ConnectionStatus"))}
  </actionList>
  <serviceStateTable>
    ${stateVar("SourceProtocolInfo", "string", true)}
    ${stateVar("SinkProtocolInfo", "string", true)}
    ${stateVar("CurrentConnectionIDs", "string", true)}
    ${stateVar("A_ARG_TYPE_ConnectionStatus", "string", false, listOf("OK", "ContentFormatMismatch", "InsufficientBandwidth", "UnreliableChannel", "Unknown"))}
    ${stateVar("A_ARG_TYPE_ConnectionManager", "string", false)}
    ${stateVar("A_ARG_TYPE_Direction", "string", false, listOf("Input", "Output"))}
    ${stateVar("A_ARG_TYPE_ProtocolInfo", "string", false)}
    ${stateVar("A_ARG_TYPE_ConnectionID", "i4", false)}
    ${stateVar("A_ARG_TYPE_AVTransportID", "i4", false)}
    ${stateVar("A_ARG_TYPE_RcsID", "i4", false)}
  </serviceStateTable>
</scpd>
"""

    private fun action(name: String, args: String) =
        "<action><name>$name</name><argumentList>$args</argumentList></action>"

    private fun arg(name: String, direction: String, related: String) =
        "<argument><name>$name</name><direction>$direction</direction>" +
            "<relatedStateVariable>$related</relatedStateVariable></argument>"

    private fun stateVar(
        name: String,
        type: String,
        sendEvents: Boolean,
        allowed: List<String>? = null,
    ): String {
        val values = allowed?.joinToString("") { "<allowedValue>$it</allowedValue>" }
            ?.let { "<allowedValueList>$it</allowedValueList>" } ?: ""
        return "<stateVariable sendEvents=\"${if (sendEvents) "yes" else "no"}\">" +
            "<name>$name</name><dataType>$type</dataType>$values</stateVariable>"
    }

    fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    fun unescape(s: String): String = s
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&#39;", "'")
        .replace("&amp;", "&")
}
