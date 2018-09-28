package com.firefly.boot.cli

import com.firefly.boot.model.Project
import com.firefly.boot.service.impl.KotlinWebScaffoldServiceImpl

/**
 * @author Pengtao Qiu
 */
fun main(args: Array<String>) {
    val project = Project(
        "/Users/bjhl/Downloads/test_project_1",
        "com.fireflysource.apple.tree", "apple-tree-website",
        "com.fireflysource.apple.tree",
        "apple.tree.fireflysource.com"
                         )
    val service = KotlinWebScaffoldServiceImpl()
    service.generate(project)
}