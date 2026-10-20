package com.boji.app.assistant

import com.boji.app.model.ModelConnection
import org.json.JSONArray
import org.json.JSONObject

internal object DeepSeekConnection {
    const val ENDPOINT="https://api.deepseek.com/chat/completions"
    const val DEFAULT_MODEL="deepseek-flash"
    fun isOfficial(endpoint: String)=runCatching { java.net.URI(endpoint).let {
        it.scheme=="https" && it.host=="api.deepseek.com" && it.port in setOf(-1,443) && it.userInfo==null && it.query==null && it.fragment==null
    } }.getOrDefault(false)
    fun default(key: String="")=ModelConnection(name="DeepSeek 官方",endpoint=ENDPOINT,model=DEFAULT_MODEL,apiKey=key)

    suspend fun models(key: String): List<ModelConnection> {
        require(key.isNotBlank() && key.length<=4096 && key.none { it=='\r' || it=='\n' }) { "请粘贴有效的 API Key" }
        val list=JSONObject(UrlConnectionTransport().get("https://api.deepseek.com/models",key)).getJSONArray("data")
        return (0 until list.length()).map { list.getJSONObject(it) }.map { item ->
            val modalities=item.optJSONArray("input_modalities") ?: item.optJSONObject("modalities")?.optJSONArray("input")
            val vision=modalities?.let { a -> (0 until a.length()).any { a.optString(it)=="image" } } ?: false
            default(key).copy(model=item.getString("id"),supportsImages=vision)
        }.filter { it.model.isNotBlank() }.distinctBy { it.model }
    }

    /** Two real provider turns, using a harmless synthetic tool; no local records are sent. */
    suspend fun check(connection: ModelConnection, transport: AssistantTransport=UrlConnectionTransport()) {
        val messages=JSONArray().put(JSONObject().put("role","user").put("content","连接检测。请调用 boji_connection_check 工具一次，然后只回复连接成功。"))
        val tool=JSONObject().put("type","function").put("function",JSONObject().put("name","boji_connection_check")
            .put("description","无副作用的应用连接自检").put("parameters",JSONObject().put("type","object").put("properties",JSONObject())))
        fun request()=JSONObject().put("model",connection.model).put("messages",messages).put("tools",JSONArray().put(tool))
            .put("thinking",JSONObject().put("type",if(connection.thinking)"enabled" else "disabled")).put("max_tokens",2048).put("stream",false).toString()
        val first=JSONObject(transport.post(ENDPOINT,connection.apiKey,request())).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        first.put("role","assistant")
        if(first.isNull("content"))first.put("content","")
        val calls=first.optJSONArray("tool_calls") ?: throw AssistantFailure("模型已连通，但工具调用检测未通过，请重试或更换官方模型。")
        require(calls.length() in 1..4)
        messages.put(first)
        for(i in 0 until calls.length()) {
            val call=calls.getJSONObject(i)
            require(call.getJSONObject("function").getString("name")=="boji_connection_check")
            messages.put(JSONObject().put("role","tool").put("tool_call_id",call.getString("id")).put("content","{\"ok\":true}"))
        }
        val final=JSONObject(transport.post(ENDPOINT,connection.apiKey,request())).getJSONArray("choices").getJSONObject(0)
        if(final.optString("finish_reason")!="stop" || final.getJSONObject("message").optString("content").isBlank())
            throw AssistantFailure("模型已连通，但连续工具调用未完成，请重试。")
    }
}
