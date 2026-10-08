package io.github.nisaka520.jevguide

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 明文端点闸门：http 只放行本机/内网，其余必须 https。
 * 这道闸挡的是「密钥 + 聊天内容（视觉模式连截图）明文裸奔给链路中间人」。
 */
class HttpGuardTest {

    @Test
    fun httpsAlwaysPasses() {
        assertNull(ChatHttp.httpGuard("https://api.deepseek.com/v1"))
        assertNull(ChatHttp.httpGuard("https://open.bigmodel.cn/api/paas/v4"))
        assertNull(ChatHttp.httpGuard("HTTPS://Example.COM/v1"))     // 大小写不敏感
    }

    @Test
    fun loopbackAndLanHttpPasses() {
        assertNull(ChatHttp.httpGuard("http://127.0.0.1:8000/v1"))
        assertNull(ChatHttp.httpGuard("http://localhost:3000/v1"))
        assertNull(ChatHttp.httpGuard("http://10.0.2.2/v1"))
        assertNull(ChatHttp.httpGuard("http://192.168.3.57:8000"))
        assertNull(ChatHttp.httpGuard("http://172.16.5.4/v1"))       // 私有段 172.16~31
        assertNull(ChatHttp.httpGuard("http://172.31.255.1/v1"))
    }

    @Test
    fun publicHttpIsRejected() {
        assertNotNull(ChatHttp.httpGuard("http://example.com/v1"))
        assertNotNull(ChatHttp.httpGuard("http://8.8.8.8/v1"))
        assertNotNull(ChatHttp.httpGuard("http://172.32.1.1/v1"))    // 172.32 不是私有段
        assertNotNull(ChatHttp.httpGuard("http://103.36.222.172/v1")) // 曾经的白名单 IP，现在必须拦
    }

    @Test
    fun rejectionMessageNamesTheHost() {
        val msg = ChatHttp.httpGuard("http://relay.example.com:8080/v1")
        assertNotNull(msg)
        assertTrue("报错要带上主机名，用户才知道改哪儿", msg!!.contains("relay.example.com"))
    }

    @Test
    fun portAndPathDoNotConfuseTheHostExtraction() {
        // 带端口、带路径、大小写混合时取的 host 必须正确
        assertNull(ChatHttp.httpGuard("http://LOCALHOST:9/v1/chat/completions"))
        assertNotNull(ChatHttp.httpGuard("http://evil.com#@127.0.0.1/v1"))
    }
}
