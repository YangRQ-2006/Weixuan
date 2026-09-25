package cn.yangrq.weixuan

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppProcessPolicyTest {
    @Test
    fun `仅主进程初始化完整 Runtime 依赖`() {
        assertTrue(AppProcessPolicy.shouldInitializeFullRuntime("cn.yangrq.weixuan", "cn.yangrq.weixuan"))
        assertFalse(AppProcessPolicy.shouldInitializeFullRuntime("cn.yangrq.weixuan:voice", "cn.yangrq.weixuan"))
        assertFalse(AppProcessPolicy.shouldInitializeFullRuntime("cn.yangrq.weixuan:voice_session", "cn.yangrq.weixuan"))
    }
}
