// SPDX-License-Identifier: GPL-3.0-or-later
package es.jjrh.bikeradar.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import es.jjrh.bikeradar.R
import es.jjrh.bikeradar.data.Prefs
import kotlinx.coroutines.launch

/**
 * Mockup-fidelity onboarding pager. Five-step structure: Permissions ->
 * Home Assistant (optional) -> Pair devices -> Radar position ->
 * Connect your eBike.
 *
 * Top: progress bar (5 segments) + Skip on the right.
 * Each step: StepHero(icon, tint) -> Mark / H1 / Sub -> step body ->
 * sticky FooterCta. The eBike step uses `Last step` instead of `Step N
 * of 5` because it's terminal.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(
    navController: NavController,
    prefs: Prefs,
    onFinished: () -> Unit,
) {
    UiTheme {
        OnboardingScreenBody(navController, prefs, onFinished)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OnboardingScreenBody(
    navController: NavController,
    prefs: Prefs,
    onFinished: () -> Unit,
) {
    val br = LocalBrColors.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 5 })

    BackHandler(enabled = pagerState.currentPage > 0) {
        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
    }

    Column(modifier = Modifier.fillMaxSize().background(br.bg).systemBarsPadding()) {
        // Top bar: progress + Skip
        TopProgress(currentPage = pagerState.currentPage, onSkip = onFinished)

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = false,
        ) { page ->
            when (page) {
                0 -> PermissionsStep(
                    prefs = prefs,
                    onContinue = { scope.launch { pagerState.animateScrollToPage(1) } },
                )
                1 -> HaStep(
                    onContinue = { scope.launch { pagerState.animateScrollToPage(2) } },
                    onSkip = { scope.launch { pagerState.animateScrollToPage(2) } },
                    prefs = prefs,
                )
                2 -> PairingStep(
                    navController = navController,
                    prefs = prefs,
                    onFinish = { scope.launch { pagerState.animateScrollToPage(3) } },
                )
                3 -> RadarPositionStep(
                    prefs = prefs,
                    onContinue = { scope.launch { pagerState.animateScrollToPage(4) } },
                )
                4 -> EBikeStep(
                    prefs = prefs,
                    onFinish = onFinished,
                )
            }
        }
    }
}

@Composable
private fun TopProgress(currentPage: Int, onSkip: () -> Unit) {
    val br = LocalBrColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (i in 0..4) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (i <= currentPage) br.brand else br.hairline2),
                )
            }
        }
        Box(modifier = Modifier.clickable(onClick = onSkip).padding(horizontal = 4.dp, vertical = 6.dp)) {
            Text(
                text = stringResource(R.string.onboarding_skip),
                color = br.fgMuted,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
