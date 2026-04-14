package com.openclaw.tv.receiver.dlna;

import com.openclaw.tv.receiver.PlaybackManager;
import com.openclaw.tv.receiver.ReceiverPlaybackRequest;
import com.openclaw.tv.receiver.ReceiverMediaKind;
import com.openclaw.tv.receiver.ReceiverState;
import org.fourthline.cling.model.ModelUtil;
import org.fourthline.cling.model.types.UnsignedIntegerFourBytes;
import org.fourthline.cling.support.model.AVTransport;
import org.fourthline.cling.support.model.MediaInfo;
import org.fourthline.cling.support.model.PositionInfo;
import org.fourthline.cling.support.model.StorageMedium;
import org.fourthline.cling.support.model.TransportInfo;
import org.fourthline.cling.support.model.TransportState;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class OpenClawDlnaSupport {

    private OpenClawDlnaSupport() {
    }

    static void prepareRequest(String uri, String metadata) {
        android.util.Log.d("OpenClawDlna", "prepareRequest uri=" + uri);
        PlaybackManager.INSTANCE.prepareDlnaRequest(
                uri,
                extractTitle(metadata, uri),
                extractMimeType(metadata)
        );
    }

    static void prepareNextRequest(String uri, String metadata) {
        if (uri == null || uri.isEmpty()) {
            return;
        }
        android.util.Log.d("OpenClawDlna", "prepareNextRequest uri=" + uri);
        PlaybackManager.INSTANCE.prepareNextDlnaRequest(
                uri,
                extractTitle(metadata, uri),
                extractMimeType(metadata)
        );
    }

    static String currentTrackMetadata() {
        ReceiverState state = PlaybackManager.INSTANCE.currentStateSnapshot();
        String uri = state.getUri() != null ? state.getUri() : "";
        if (uri.isEmpty()) {
            return "NOT_IMPLEMENTED";
        }
        return buildMetadata(uri, state.getTitle(), state.getMimeType());
    }

    static String nextTrackUri() {
        ReceiverPlaybackRequest request = PlaybackManager.INSTANCE.pendingNextDlnaRequestSnapshot();
        return request != null ? request.getUri() : "NOT_IMPLEMENTED";
    }

    static String nextTrackMetadata() {
        ReceiverPlaybackRequest request = PlaybackManager.INSTANCE.pendingNextDlnaRequestSnapshot();
        if (request == null) {
            return "NOT_IMPLEMENTED";
        }
        return buildMetadata(request.getUri(), request.getTitle(), request.getMimeType());
    }

    static void syncTransport(AVTransport transport) {
        ReceiverState state = PlaybackManager.INSTANCE.currentStateSnapshot();
        String uri = state.getUri() != null ? state.getUri() : "";
        String metadata = "NOT_IMPLEMENTED";
        long durationSeconds = Math.max(0L, state.getDurationMs() / 1000L);
        long positionSeconds = Math.max(0L, state.getPositionMs() / 1000L);

        transport.setMediaInfo(
                new MediaInfo(
                        uri,
                        metadata,
                        new UnsignedIntegerFourBytes(uri.isEmpty() ? 0 : 1),
                        ModelUtil.toTimeString(durationSeconds),
                        StorageMedium.NETWORK
                )
        );
        transport.setPositionInfo(
                new PositionInfo(
                        uri.isEmpty() ? 0 : 1,
                        ModelUtil.toTimeString(durationSeconds),
                        metadata,
                        uri,
                        ModelUtil.toTimeString(positionSeconds),
                        ModelUtil.toTimeString(positionSeconds),
                        Integer.MAX_VALUE,
                        Integer.MAX_VALUE
                )
        );
        transport.setTransportInfo(new TransportInfo(currentTransportState(state)));
    }

    static TransportState currentTransportState(ReceiverState state) {
        if (state.getMediaKind() == ReceiverMediaKind.Idle) {
            return TransportState.NO_MEDIA_PRESENT;
        }
        if (state.isPlaying()) {
            return TransportState.PLAYING;
        }
        return TransportState.STOPPED;
    }

    static long parseTargetMillis(String target) {
        if (target == null || target.isEmpty()) {
            return 0L;
        }
        String[] parts = target.split(":");
        if (parts.length != 3) {
            return 0L;
        }
        long hours = parseLong(parts[0]);
        long minutes = parseLong(parts[1]);
        double seconds = parseDouble(parts[2]);
        return (long) (((hours * 3600) + (minutes * 60) + seconds) * 1000L);
    }

    static String extractTitle(String metadata, String fallbackUri) {
        String title = extractTagValue(metadata, "dc:title");
        if (title == null) {
            title = extractTagValue(metadata, "title");
        }
        if (title != null && !title.isEmpty()) {
            return title;
        }
        try {
            return URI.create(fallbackUri).getPath();
        } catch (Exception ignored) {
            return fallbackUri;
        }
    }

    static String extractMimeType(String metadata) {
        String protocolInfo = extractAttributeValue(metadata, "res", "protocolInfo");
        if (protocolInfo == null || protocolInfo.isEmpty()) {
            return null;
        }
        String[] parts = protocolInfo.split(":");
        return parts.length >= 3 ? parts[2] : null;
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static double parseDouble(String value) {
        try {
            return Double.parseDouble(value.trim());
        } catch (Exception ignored) {
            return 0.0;
        }
    }

    private static String extractTagValue(String xml, String tag) {
        if (xml == null || xml.isEmpty()) {
            return null;
        }
        Matcher matcher = Pattern.compile("<" + Pattern.quote(tag) + ">(.*?)</" + Pattern.quote(tag) + ">", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(xml);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private static String extractAttributeValue(String xml, String tag, String attribute) {
        if (xml == null || xml.isEmpty()) {
            return null;
        }
        Matcher matcher = Pattern.compile("<" + tag + "[^>]*" + attribute + "=\"([^\"]+)\"[^>]*>", Pattern.CASE_INSENSITIVE).matcher(xml);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private static String buildMetadata(String uri, String title, String mimeType) {
        String safeTitle = escapeXml(title != null && !title.isEmpty() ? title : extractTitle(null, uri));
        String safeUri = escapeXml(uri);
        String safeMime = escapeXml(mimeType != null && !mimeType.isEmpty() ? mimeType : "video/mp4");
        return ""
                + "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" "
                + "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" "
                + "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">"
                + "<item id=\"0\" parentID=\"0\" restricted=\"1\">"
                + "<dc:title>" + safeTitle + "</dc:title>"
                + "<res protocolInfo=\"http-get:*:" + safeMime + ":*\">" + safeUri + "</res>"
                + "</item>"
                + "</DIDL-Lite>";
    }

    private static String escapeXml(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
