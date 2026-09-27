package com.agit.app.ui

import android.net.Uri
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.Composable
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.agit.app.ui.screen.AuthScreen
import com.agit.app.ui.screen.ConnectRepoScreen
import com.agit.app.ui.screen.CommitDetailScreen
import com.agit.app.ui.screen.CommitScreen
import com.agit.app.ui.screen.FileEditScreen
import com.agit.app.ui.screen.FileViewScreen
import com.agit.app.ui.screen.HomeScreen
import com.agit.app.ui.screen.MyConnectionsScreen
import com.agit.app.ui.screen.NewRepoScreen
import com.agit.app.ui.screen.RepoDetailScreen
import com.agit.app.ui.screen.RepoSettingsScreen
import com.agit.app.ui.screen.PublicRepoScreen
import com.agit.app.ui.screen.SplashScreen

object Routes {
    const val SPLASH = "splash"
    const val LOGIN = "login"
    const val REGISTER = "register"
    const val HOME = "home"
    const val NEW_REPO = "new_repo"
    const val CONNECT_REPO = "connect_repo"
    const val MY_CONNECTIONS = "my_connections"

    fun repo(repoId: String) = "repo/$repoId"
    fun commitList(repoId: String) = "repo/$repoId/commit"
    fun commitDetail(repoId: String, commitId: String) = "repo/$repoId/commit/$commitId"
    fun fileView(repoId: String, path: String) = "repo/$repoId/file/${Uri.encode(path)}"
    fun fileEdit(repoId: String, path: String) = "repo/$repoId/edit/${Uri.encode(path)}"
    fun publicRepo(rid: String) = "public_repo/$rid"
    fun repoSettings(repoId: String) = "repo/$repoId/settings"
}

private fun emptyEnter(): EnterTransition = EnterTransition.None
private fun emptyExit(): ExitTransition = ExitTransition.None
private fun emptyPopEnter(): EnterTransition = EnterTransition.None
private fun emptyPopExit(): ExitTransition = ExitTransition.None

@Composable
fun AppNav(nc: NavHostController) {
    NavHost(
        navController = nc,
        startDestination = Routes.SPLASH,
        enterTransition = { emptyEnter() },
        exitTransition = { emptyExit() },
        popEnterTransition = { emptyPopEnter() },
        popExitTransition = { emptyPopExit() }
    ) {
        composable(Routes.SPLASH) { SplashScreen(nc) }
        composable(Routes.LOGIN) { AuthScreen(nc, mode = "login") }
        composable(Routes.REGISTER) { AuthScreen(nc, mode = "register") }
        composable(Routes.HOME) { HomeScreen(nc) }
        composable(Routes.NEW_REPO) { NewRepoScreen(nc) }
        composable(Routes.CONNECT_REPO) { ConnectRepoScreen(nc) }
        composable(Routes.MY_CONNECTIONS) { MyConnectionsScreen(nc) }
        composable(
            Routes.repo("{repoId}"),
            arguments = listOf(navArgument("repoId") { type = NavType.StringType })
        ) { RepoDetailScreen(nc) }
        composable(
            Routes.commitList("{repoId}"),
            arguments = listOf(navArgument("repoId") { type = NavType.StringType })
        ) { CommitScreen(nc) }
        composable(
            "repo/{repoId}/commit/{commitId}",
            arguments = listOf(
                navArgument("repoId") { type = NavType.StringType },
                navArgument("commitId") { type = NavType.StringType }
            )
        ) { CommitDetailScreen(nc) }
        composable(
            "repo/{repoId}/file/{path}",
            arguments = listOf(
                navArgument("repoId") { type = NavType.StringType },
                navArgument("path") { type = NavType.StringType }
            )
        ) { FileViewScreen(nc) }
        composable(
            "repo/{repoId}/edit/{path}",
            arguments = listOf(
                navArgument("repoId") { type = NavType.StringType },
                navArgument("path") { type = NavType.StringType }
            )
        ) { FileEditScreen(nc) }
        composable(
            Routes.publicRepo("{repoId}"),
            arguments = listOf(navArgument("repoId") { type = NavType.StringType })
        ) { PublicRepoScreen(nc) }
        composable(
            Routes.repoSettings("{repoId}"),
            arguments = listOf(navArgument("repoId") { type = NavType.StringType })
        ) { RepoSettingsScreen(nc) }
    }
}
