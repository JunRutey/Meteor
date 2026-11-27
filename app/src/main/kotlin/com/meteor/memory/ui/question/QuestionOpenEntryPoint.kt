package com.meteor.memory.ui.question

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * 从 `Application` 取 [QuestionOpenRequest] 的入口（ADR-068）。
 *
 * 通知点击要在导航图里跳转，而 `MeteorNavHost` 拿不到一个「Hilt ViewModel」：
 * [QuestionOpenRequest] 是普通 `@Singleton`，`hiltViewModel()` 只认
 * `@HiltViewModel` 的 ViewModel。给 `MeteorNavHost` 加参数则要改 `MeteorRoot`，
 * 那属于另一份改动的范围。`@EntryPoint` 是 Hilt 为此准备的官方出口：不新增
 * 依赖边，也不把单例塞进 Compose 参数。
 *
 * 调用方固定写法（`Context` 必须是 Application context）：
 * `EntryPointAccessors.fromApplication(context.applicationContext, QuestionOpenEntryPoint::class.java)`
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface QuestionOpenEntryPoint {
    fun questionOpenRequest(): QuestionOpenRequest
}
