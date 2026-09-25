package cn.yangrq.weixuan.agent.voice

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text

private val assistantSuggestions = listOf(
    "帮我总结当前屏幕内容",
    "这个页面怎么操作",
    "屏幕上有什么值得注意的信息",
)

private val suggestionBackground = Color(0xFFF2F3F5)

@Composable
internal fun EtaAssistantSuggestions(
    onSuggestionClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    keyboardVisible: Boolean = false,
) {
    val sidePadding by animateDpAsState(
        targetValue = if (keyboardVisible) 16.dp else 20.dp,
        animationSpec = tween(900, delayMillis = 100, easing = FastOutSlowInEasing),
        label = "assistant_suggestions_keyboard_padding",
    )

    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = EnterTransition.None,
        exit = shrinkVertically(
            shrinkTowards = Alignment.Bottom,
            animationSpec = tween(180, easing = FastOutSlowInEasing),
        ) + fadeOut(tween(140)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = sidePadding, end = sidePadding, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            assistantSuggestions.forEachIndexed { index, suggestion ->
                val transform = remember(suggestion) { Animatable(0f) }
                val opacity = remember(suggestion) { Animatable(0f) }
                LaunchedEffect(visible) {
                    if (visible) {
                        transform.snapTo(0f)
                        opacity.snapTo(0f)
                        delay(900L + when (index) {
                            0 -> 200L
                            1 -> 117L
                            else -> 0L
                        })
                        coroutineScope {
                            launch {
                                transform.animateTo(
                                    targetValue = 1f,
                                    animationSpec = spring(dampingRatio = 0.7f, stiffness = 109.66f),
                                )
                            }
                            launch { opacity.animateTo(1f, tween(350)) }
                        }
                    } else {
                        coroutineScope {
                            launch { transform.animateTo(0f, tween(140)) }
                            launch { opacity.animateTo(0f, tween(140)) }
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .height(32.dp)
                        .graphicsLayer {
                            alpha = opacity.value
                            scaleX = transform.value
                            scaleY = transform.value
                            translationY = (1f - transform.value) * 18.dp.toPx()
                        }
                        .clip(CircleShape)
                        .background(suggestionBackground)
                        .clickable(enabled = visible) { onSuggestionClick(suggestion) }
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = suggestion,
                        color = Color(0xE6000000),
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
