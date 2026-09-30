package com.savereplaybufferforobs;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.lang.reflect.Field;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.WebSocket;
import okio.ByteString;
import org.junit.Test;
import static org.junit.Assert.*;

public class ObsClipTest
{
    private final Gson gson = new Gson();
    private String error;
    private String sent;
    private String chat;
    private final DisplaysExceptions feedback = new DisplaysExceptions()
    {
        public void setObsException(ObsException exception) { error = exception.getMessage(); }
        public void clearObsException() { error = null; }
        public void clearObsException(ObsException exception) { if (exception.getMessage().equals(error)) { error = null; } }
        public void showChatMessage(String message) { chat = message; }
    };
    private final WebSocketClientForObs client = new WebSocketClientForObs(new OkHttpClient(), gson, "localhost", 4455, "", feedback);
    private final WebSocketListenerForObs listener = new WebSocketListenerForObs(client, gson, "", feedback);

    @Test
    public void sendsExactVendorContractAndTellsYouWhenDisconnected() throws Exception
    {
        client.saveClip(60);
        assertEquals("Your replay clip wasn't saved because OBS isn't connected.", chat);
        assertNull(error); // One-off problems go to chat, not a lasting overlay.
        Field socket = WebSocketClientForObs.class.getDeclaredField("webSocket");
        socket.setAccessible(true);
        socket.set(client, new WebSocket()
        {
            public Request request() { return new Request.Builder().url("http://localhost").build(); }
            public long queueSize() { return 0; }
            public boolean send(String text) { sent = text; return true; }
            public boolean send(ByteString bytes) { return false; }
            public boolean close(int code, String reason) { return true; }
            public void cancel() { }
        });
        client.setConnected(true);
        client.saveClip(1234);
        JsonObject envelope = gson.fromJson(sent, JsonObject.class);
        assertEquals(6, envelope.get("op").getAsInt());
        JsonObject request = envelope.getAsJsonObject("d");
        assertEquals("CallVendorRequest", request.get("requestType").getAsString());
        JsonObject data = request.getAsJsonObject("requestData");
        assertEquals("replay-buffer-pro", data.get("vendorName").getAsString());
        assertEquals("SaveClip", data.get("requestType").getAsString());
        assertEquals(1234, data.getAsJsonObject("requestData").get("durationSeconds").getAsInt());
        client.disconnect();
        sent = null;
        client.saveClip(60);
        assertNull(sent);
    }

    @Test
    public void refusedClipsExplainTheFixInChat()
    {
        respond("{\"result\":false,\"comment\":\"Unknown vendor\"}", "{}");
        assertEquals("OBS couldn't save your replay clip. Make sure the Replay Buffer Pro OBS plugin is installed and up to date.", chat);
        respond("{\"result\":true}", "{\"responseData\":{\"accepted\":false,\"error\":\"buffer-inactive\"}}");
        assertEquals("OBS couldn't save your replay clip. The OBS replay buffer wasn't running; start it in OBS.", chat);
        respond("{\"result\":true}", "{}");
        assertTrue(chat.startsWith("OBS couldn't save your replay clip."));
        assertNull(error);

        chat = null;
        respond("{\"result\":true}", "{\"responseData\":{\"accepted\":true}}");
        assertNull(chat); // A normal save stays quiet.
    }

    @Test
    public void healthWarningsStayOnlyWhileTheBufferIsOff()
    {
        health(false);
        assertTrue(error.contains("not active"));
        respond("{\"result\":true}", "{\"responseData\":{\"accepted\":true}}");
        assertTrue(error.contains("not active")); // A successful clip leaves a real health warning alone.
        health(true);
        assertNull(error);
    }

    @Test
    public void clampedClipWarnsOnceInChat()
    {
        respond("{\"result\":true}", "{\"responseData\":{\"accepted\":true,\"durationSeconds\":3600,\"clamped\":true}}");
        assertNull(error);
        assertEquals("Your replay clip was shortened to 1:00:00, the length of your OBS replay buffer."
            + " Increase it in OBS to capture whole activities.", chat);
    }

    private void health(boolean active)
    {
        listener.onMessage(null, "{\"op\":7,\"d\":{\"requestType\":\"GetReplayBufferStatus\",\"responseData\":{\"outputActive\":" + active + "}}}");
    }

    private void respond(String status, String data)
    {
        listener.onMessage(null, "{\"op\":7,\"d\":{\"requestId\":\"runelite-duration-req\",\"requestType\":\"CallVendorRequest\",\"requestStatus\":" + status + ",\"responseData\":" + data + "}}");
    }
}
