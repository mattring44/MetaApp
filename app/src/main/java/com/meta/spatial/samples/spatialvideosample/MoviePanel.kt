/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * Licensed under the MIT license in the LICENSE file in the root directory.
 */
package com.spectrum.spectrumsports

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import com.meta.spatial.toolkit.SpatialActivityManager

data class Movie(val id: Int, val uri: Uri, val title: String)

class MovieViewModel : ViewModel() {
    val movies = listOf(Movie(1, Uri.parse(SpatialGenPlaybackConfig.STREAM_URL), "SpatialGen Widevine sample"))

    fun selectMovie(movie: Movie) {
        SpatialActivityManager.executeOnVrActivity<SpatialVideoSampleActivity> { activity ->
            activity.setVideo(movie.uri)
            activity.playVideo()
        }
    }
}

@Composable
fun MovieListScreen(viewModel: MovieViewModel) {
    Column(
        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF1C2E33)).padding(16.dp)
    ) {
        Text(
            text = "Spatial Video Library",
            fontFamily = FontFamily(Font(R.font.noto_sans_regular)),
            fontSize = 20.sp,
            color = Color(0xFFF0F0F0),
            modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
        )
        LazyVerticalGrid(columns = GridCells.Fixed(1)) {
            items(viewModel.movies) { movie ->
                Column(Modifier.fillMaxWidth().clickable { viewModel.selectMovie(movie) }) {
                    // Encrypted media cannot provide a thumbnail via MediaMetadataRetriever.
                    Box(
                        modifier = Modifier.fillMaxWidth().height(150.dp).padding(8.dp)
                            .clip(RoundedCornerShape(16.dp)).background(Color(0xFF30474E)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Play stream", color = Color.White, fontSize = 18.sp)
                    }
                    Text(
                        movie.title, color = Color(0xFFF0F0F0), fontSize = 14.sp,
                        modifier = Modifier.padding(start = 8.dp, bottom = 10.dp),
                    )
                }
            }
        }
    }
}

class MoviePanel : ComponentActivity() {
    override fun onCreate(savedInstanceBundle: Bundle?) {
        super.onCreate(savedInstanceBundle)
        val viewModel: MovieViewModel by viewModels()
        setContent { MovieListScreen(viewModel) }
    }
}
