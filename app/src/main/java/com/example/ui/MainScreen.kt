package com.example.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.outlined.Assessment
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.screens.BenchmarkScreen
import com.example.ui.screens.ProjectsScreen
import com.example.ui.screens.StudioScreen
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.DarkBorder
import com.example.ui.theme.DarkSurface

enum class AppTab(val label: String) {
    STUDIO("Studio"),
    BENCHMARKS("Benchmarks"),
    PROJECTS("Projects")
}

@Composable
fun MainScreen(
    viewModel: StudioViewModel = viewModel()
) {
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            NavigationBar(
                containerColor = DarkSurface,
                windowInsets = WindowInsets.navigationBars,
                modifier = Modifier.testTag("app_navigation_bar")
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = {
                        Icon(
                            if (selectedTab == 0) Icons.Filled.Brush else Icons.Outlined.Brush,
                            contentDescription = "Studio"
                        )
                    },
                    label = { Text("Studio") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color(0xFF032830),
                        indicatorColor = CyanPrimary,
                        selectedTextColor = CyanPrimary,
                        unselectedIconColor = Color(0xFF94A3B8),
                        unselectedTextColor = Color(0xFF94A3B8)
                    ),
                    modifier = Modifier.testTag("nav_tab_studio")
                )

                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = {
                        Icon(
                            if (selectedTab == 1) Icons.Filled.Assessment else Icons.Outlined.Assessment,
                            contentDescription = "Benchmarks"
                        )
                    },
                    label = { Text("Benchmarks") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color(0xFF032830),
                        indicatorColor = CyanPrimary,
                        selectedTextColor = CyanPrimary,
                        unselectedIconColor = Color(0xFF94A3B8),
                        unselectedTextColor = Color(0xFF94A3B8)
                    ),
                    modifier = Modifier.testTag("nav_tab_benchmarks")
                )

                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = {
                        Icon(
                            if (selectedTab == 2) Icons.Filled.Folder else Icons.Outlined.Folder,
                            contentDescription = "Projects"
                        )
                    },
                    label = { Text("Projects") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color(0xFF032830),
                        indicatorColor = CyanPrimary,
                        selectedTextColor = CyanPrimary,
                        unselectedIconColor = Color(0xFF94A3B8),
                        unselectedTextColor = Color(0xFF94A3B8)
                    ),
                    modifier = Modifier.testTag("nav_tab_projects")
                )
            }
        },
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        when (selectedTab) {
            0 -> StudioScreen(viewModel = viewModel, modifier = Modifier.padding(innerPadding))
            1 -> BenchmarkScreen(
                viewModel = viewModel,
                onNavigateToStudio = { selectedTab = 0 },
                modifier = Modifier.padding(innerPadding)
            )
            2 -> ProjectsScreen(
                viewModel = viewModel,
                onNavigateToStudio = { selectedTab = 0 },
                modifier = Modifier.padding(innerPadding)
            )
        }
    }
}
