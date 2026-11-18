package com.example.coverdesign.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector

@Composable
fun AppBottomNavBar(
    currentRoute   : String?,
    onItemSelected : (BottomNavItem) -> Unit
) {
    NavigationBar {
        bottomNavItems.forEach { item ->
            val selected = currentRoute == item.route
            NavigationBarItem(
                selected = selected,
                onClick  = { onItemSelected(item) },
                icon     = {
                    Icon(
                        imageVector        = if (selected) item.iconSelected else item.icon,
                        contentDescription = item.label
                    )
                },
                label    = { Text(item.label) }
            )
        }
    }
}

sealed class BottomNavItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val iconSelected: ImageVector = icon
) {
    object Home : BottomNavItem(
        "home", "Home", Icons.Outlined.Home,
        Icons.Filled.Home
    )

    object Orders : BottomNavItem(
        "orders", "Orders", Icons.Outlined.ShoppingBag,
        Icons.Filled.ShoppingBag
    )

    object Scanner : BottomNavItem(
        Screen.Gait.route, "Gait",
        Icons.Outlined.DirectionsWalk, Icons.Filled.DirectionsWalk
    )

    object Profile : BottomNavItem(
        "profile", "Profile", Icons.Outlined.Person,
        Icons.Filled.Person
    )
}

val bottomNavItems = listOf(
    BottomNavItem.Home,
    BottomNavItem.Orders,
    BottomNavItem.Scanner,
    BottomNavItem.Profile
)
