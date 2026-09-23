package io.github.nisaka520.jevguide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContactsTest {

    private val 妈妈 = Contact(listOf("妈妈", "妈", "老妈"), "家人", "女", "生日三月")
    private val 张伟 = Contact(listOf("张伟", "伟哥"), "同事", "男", "市场部")
    private val 小明 = Contact(listOf("小明"), "普通朋友", "未知")
    private val 小明妈妈 = Contact(listOf("小明妈妈", "小明妈"), "家人", "女")

    @Test
    fun normalizeStripsGroupCountAndWhitespace() {
        assertEquals("项目组", Contacts.normalize("项目组 (8)"))
        assertEquals("项目组", Contacts.normalize("项目组（12）"))
        assertEquals("项目组", Contacts.normalize(" 项目 组 （3/9） "))
        assertEquals("abc", Contacts.normalize("ABC"))
    }

    @Test
    fun matchesByNameAndByAlias() {
        val list = listOf(妈妈, 张伟)
        assertEquals("家人", Contacts.match(list, "妈妈", "妈妈")!!.relation)
        assertEquals("家人", Contacts.match(list, "老妈", "老妈")!!.relation)
        assertEquals("同事", Contacts.match(list, "张伟", "张伟")!!.relation)
        assertNull(Contacts.match(list, "陌生人甲", "甲乙丙"))
    }

    @Test
    fun longestAliasWins() {
        val list = listOf(小明, 小明妈妈)
        assertEquals("家人", Contacts.match(list, "小明妈妈", "小明妈妈")!!.relation)
        assertEquals("普通朋友", Contacts.match(list, "小明", "小明")!!.relation)
    }

    @Test
    fun groupTitleStillMatchesTheContactInside() {
        val list = listOf(张伟)
        assertEquals("同事", Contacts.match(list, "张伟 (8)", "张伟")!!.relation)
    }

    @Test
    fun jsonRoundTripsAndRepairsInvalidValues() {
        val text = Contacts.toJson(listOf(妈妈, 张伟))
        val back = Contacts.fromJson(text)
        assertEquals(2, back.size)
        assertEquals(listOf("妈妈", "妈", "老妈"), back[0].names)
        assertEquals("生日三月", back[0].note)

        val broken = Contacts.fromJson("""[{"names":["甲"],"relation":"老板","sex":"保密"}]""")
        assertEquals(1, broken.size)
        assertEquals("普通朋友", broken[0].relation)   // 非法档位回落
        assertEquals("未知", broken[0].sex)
        assertEquals(emptyList<Contact>(), Contacts.fromJson("[]"))
        assertEquals(emptyList<Contact>(), Contacts.fromJson("坏数据"))
    }

    @Test
    fun lineFormatRoundTrips() {
        val c = Contacts.parseLine("妈妈,妈,老妈=家人/女/生日三月")!!
        assertEquals(listOf("妈妈", "妈", "老妈"), c.names)
        assertEquals("家人", c.relation)
        assertEquals("女", c.sex)
        assertEquals("生日三月", c.note)
        assertEquals("妈妈,妈,老妈=家人/女/生日三月", Contacts.formatLine(c))

        val simple = Contacts.parseLine("小红")!!
        assertEquals("普通朋友", simple.relation)
        assertEquals("未知", simple.sex)
        assertNull(Contacts.parseLine(""))
        assertNull(Contacts.parseLine("# 这是注释"))
    }

    @Test
    fun fallbackUsesTheTitle() {
        val f = Contacts.fallback("张三")
        assertEquals("张三", f.display)
        assertEquals("普通朋友", f.relation)
    }
}
