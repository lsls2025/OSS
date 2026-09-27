package com.agit.app.data

import android.app.Application
import com.agit.app.BuildConfig
import com.agit.app.data.db.DbHelper
import com.agit.app.data.remote.AuthApi
import com.agit.app.data.remote.AuthRepository
import com.agit.app.data.remote.RepoApi
import com.agit.app.data.remote.RepoRepository
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File

/**
 * 应用级依赖容器：持有数据库、会话与版本控制引擎工厂。
 */
object AppContainer {

    private lateinit var app: Application
    val db: DbHelper by lazy { DbHelper(app) }
    lateinit var session: SessionManager
    var homeTab: Int = 0

    fun init(application: Application) {
        app = application
        session = SessionManager(application)
    }

    fun repoRoot(repoId: String): File = File(app.filesDir, "repos/$repoId")

    fun vcs(repoId: String): com.agit.app.vcs.VcsEngine = com.agit.app.vcs.VcsEngine(repoRoot(repoId), db)

    // ---------------- 业务封装 ----------------

    val authRepository: AuthRepository by lazy { AuthRepository(createAuthApi()) }
    val repoRepository: RepoRepository by lazy { RepoRepository(createRepoApi()) }

    /** 统一添加 Authorization header 的拦截器。 */
    private val authInterceptor = Interceptor { chain ->
        val original = chain.request()
        val token = session.token()
        val request = if (token != null && original.header("Authorization") == null) {
            original.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        } else original
        chain.proceed(request)
    }

    private fun buildClient(): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY }
        return OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(logging)
            .build()
    }

    private fun createAuthApi(): AuthApi {
        return Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(buildClient())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(AuthApi::class.java)
    }

    private fun createRepoApi(): RepoApi {
        return Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(buildClient())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(RepoApi::class.java)
    }

    private val BASE_URL: String = BuildConfig.API_BASE_URL

    fun createRepo(owner: com.agit.app.data.model.User, name: String, description: String, isPrivate: Boolean): com.agit.app.data.model.Repo {
        val now = System.currentTimeMillis()
        val repo = com.agit.app.data.model.Repo(
            id = com.agit.app.util.Crypto.randomHex(16),
            name = name,
            description = description,
            ownerId = owner.id,
            ownerName = owner.displayNameOrUser(),
            isPrivate = isPrivate,
            defaultBranch = "main",
            createdAt = now,
            updatedAt = now
        )
        db.insertRepo(repo)
        vcs(repo.id).initRepo(repo.id, owner.id, owner.displayNameOrUser(), name, description)
        return repo
    }

    fun commitToRepo(
        repoId: String,
        author: com.agit.app.data.model.User,
        message: String,
        selectedPaths: Set<String>?
    ): com.agit.app.data.model.CommitRecord? {
        val rec = vcs(repoId).commit(repoId, author.id, author.displayNameOrUser(), message, selectedPaths)
        if (rec != null) db.touchRepo(repoId)
        return rec
    }

    fun deviceName(): String = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim()
}
