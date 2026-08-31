package com.sieve.app.ui.nav

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.sieve.app.ui.common.EmptyState

/**
 * Root navigation: a Scaffold with the five-destination bottom bar and a NavHost. Screen bodies are
 * stubs until Tasks 9/11/13/14/15 replace them; About is a pushed route (no bar).
 */
@Composable
fun SieveNavHost() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBar = currentRoute == null || Dest.entries.any { it.route == currentRoute }

    Scaffold(
        bottomBar = {
            if (showBar) {
                NavigationBar {
                    Dest.entries.forEach { d ->
                        NavigationBarItem(
                            modifier = Modifier.testTag("nav_${d.route}"),
                            selected = currentRoute == d.route,
                            onClick = {
                                navController.navigate(d.route) {
                                    launchSingleTop = true
                                    restoreState = true
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                }
                            },
                            icon = { Icon(d.icon, contentDescription = d.label) },
                            label = { Text(d.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Dest.DOWNLOAD.route,
            modifier = Modifier.padding(padding),
        ) {
            Dest.entries.forEach { d ->
                composable(d.route) {
                    EmptyState(icon = d.icon, title = d.label, subtitle = "${d.route}-screen")
                }
            }
            composable(ROUTE_ABOUT) {
                EmptyState(icon = Icons.Filled.Info, title = "About", subtitle = "about-screen")
            }
        }
    }
}
