package com.firefly.example.kotlin.test

import com.firefly.example.kotlin.coffee.store.ktCtx
import com.firefly.example.kotlin.coffee.store.utils.DBUtils
import com.firefly.kotlin.ext.context.getBean
import org.junit.Before

/**
 * @author Pengtao Qiu
 */
open class TestBase {

    @Before
    fun before() {
        val dbUtils = ktCtx.getBean<DBUtils>()
        dbUtils.createTables()
        dbUtils.initializeData()
        println("init test data")
    }
}