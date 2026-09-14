package com.navirotation.ui.map

import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.navirotation.R
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.upstream.DefaultDataSource
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import com.google.android.exoplayer2.upstream.RawResourceDataSource
import com.google.android.exoplayer2.ui.PlayerView
import androidx.appcompat.widget.Toolbar


class CctvPlayerActivity : AppCompatActivity() {

    private lateinit var player: ExoPlayer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cctv_player)

        // Intent 로부터 URL 또는 리소스 URI 가져오기
        val streamUrl = intent.getStringExtra("streamUrl") ?: run {
            finish()
            return
        }
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener {
            finish()
        }

        // ExoPlayer 생성 및 뷰에 연결
        player = ExoPlayer.Builder(this).build()
        findViewById<PlayerView>(R.id.playerView).player = player

        val uri = Uri.parse(streamUrl)

        // ──────────────────────────────────────────────────
        // 스킴이 "rawresource" 면 로컬 리소스로 처리, 그 외는 HTTP/FILE
        val factory: DataSource.Factory = when (uri.scheme) {
            RawResourceDataSource.RAW_RESOURCE_SCHEME ->
                DataSource.Factory { RawResourceDataSource(this) }

            else ->
                DefaultDataSource.Factory(this, DefaultHttpDataSource.Factory())
        }
        // ──────────────────────────────────────────────────

        // ProgressiveMediaSource 로 생성 후 재생
        val mediaSource = ProgressiveMediaSource.Factory(factory)
            .createMediaSource(MediaItem.fromUri(uri))

        player.setMediaSource(mediaSource)
        player.prepare()
        player.playWhenReady = true
    }

    override fun onDestroy() {
        super.onDestroy()
        player.release()
    }

    override fun onBackPressed() {
        player.stop()
        super.onBackPressed()
    }
}
