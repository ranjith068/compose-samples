/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.jetcaster.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.jetcaster.core.data.database.model.Category
import com.example.jetcaster.core.data.database.model.EpisodeToPodcast
import com.example.jetcaster.core.data.database.model.Podcast
import com.example.jetcaster.core.data.database.model.PodcastWithExtraInfo
import com.example.jetcaster.core.data.di.Graph
import com.example.jetcaster.core.data.domain.FilterableCategoriesUseCase
import com.example.jetcaster.core.data.domain.GetLatestFollowedEpisodesUseCase
import com.example.jetcaster.core.data.domain.PodcastCategoryFilterUseCase
import com.example.jetcaster.core.data.model.FilterableCategoriesModel
import com.example.jetcaster.core.data.model.PodcastCategoryFilterResult
import com.example.jetcaster.core.data.repository.EpisodeStore
import com.example.jetcaster.core.data.repository.PodcastStore
import com.example.jetcaster.core.data.repository.PodcastsRepository
import com.example.jetcaster.util.combine
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val podcastsRepository: PodcastsRepository = Graph.podcastRepository,
    private val podcastStore: PodcastStore = Graph.podcastStore,
    private val episodeStore: EpisodeStore = Graph.episodeStore,
    private val getLatestFollowedEpisodesUseCase: GetLatestFollowedEpisodesUseCase =
        Graph.getLatestFollowedEpisodesUseCase,
    private val podcastCategoryFilterUseCase: PodcastCategoryFilterUseCase =
        Graph.podcastCategoryFilterUseCase,
    private val filterableCategoriesUseCase: FilterableCategoriesUseCase =
        Graph.filterableCategoriesUseCase
) : ViewModel() {
    // Holds our currently selected podcast in the library
    private val selectedLibraryPodcast = MutableStateFlow<Podcast?>(null)
    // Holds our currently selected home category
    private val selectedHomeCategory = MutableStateFlow(HomeCategory.Discover)
    // Holds the currently available home categories
    private val homeCategories = MutableStateFlow(HomeCategory.entries)
    // Holds our currently selected category
    private val _selectedCategory = MutableStateFlow<Category?>(null)
    // Holds our view state which the UI collects via [state]
    private val _state = MutableStateFlow(HomeViewState())
    // Holds the view state if the UI is refreshing for new data
    private val refreshing = MutableStateFlow(false)

    val state: StateFlow<HomeViewState>
        get() = _state

    init {
        viewModelScope.launch {
            // Combines the latest value from each of the flows, allowing us to generate a
            // view state instance which only contains the latest values.
            combine(
                homeCategories,
                selectedHomeCategory,
                podcastStore.followedPodcastsSortedByLastEpisode(limit = 10),
                refreshing,
                _selectedCategory.flatMapLatest { selectedCategory ->
                    filterableCategoriesUseCase(selectedCategory)
                },
                _selectedCategory.flatMapLatest {
                    podcastCategoryFilterUseCase(it)
                },
                selectedLibraryPodcast.flatMapLatest {
                    episodeStore.episodesInPodcast(
                        podcastUri = it?.uri ?: "",
                        limit = 20
                    )
                }
            ) { homeCategories,
                homeCategory,
                podcasts,
                refreshing,
                filterableCategories,
                podcastCategoryFilterResult,
                libraryEpisodes ->

                _selectedCategory.value = filterableCategories.selectedCategory

                // Override selected home category to show 'DISCOVER' if there are no
                // featured podcasts
                selectedHomeCategory.value =
                    if (podcasts.isEmpty()) HomeCategory.Discover else homeCategory

                HomeViewState(
                    homeCategories = homeCategories,
                    selectedHomeCategory = homeCategory,
                    featuredPodcasts = podcasts.toPersistentList(),
                    refreshing = refreshing,
                    filterableCategoriesModel = filterableCategories,
                    podcastCategoryFilterResult = podcastCategoryFilterResult,
                    libraryEpisodes = libraryEpisodes,
                    errorMessage = null, /* TODO */
                )
            }.catch { throwable ->
                // TODO: emit a UI error here. For now we'll just rethrow
                throw throwable
            }.collect {
                _state.value = it
            }
        }

        refresh(force = false)
    }

    private fun refresh(force: Boolean) {
        viewModelScope.launch {
            runCatching {
                refreshing.value = true
                podcastsRepository.updatePodcasts(force)
            }
            // TODO: look at result of runCatching and show any errors

            refreshing.value = false
        }
    }

    fun onCategorySelected(category: Category) {
        _selectedCategory.value = category
    }

    fun onHomeCategorySelected(category: HomeCategory) {
        selectedHomeCategory.value = category
    }

    fun onPodcastUnfollowed(podcastUri: String) {
        viewModelScope.launch {
            podcastStore.unfollowPodcast(podcastUri)
        }
    }

    fun onTogglePodcastFollowed(podcastUri: String) {
        viewModelScope.launch {
            podcastStore.togglePodcastFollowed(podcastUri)
        }
    }

    fun onLibraryPodcastSelected(podcast: Podcast?) {
        selectedLibraryPodcast.value = podcast
    }
}

enum class HomeCategory {
    Library, Discover
}

data class HomeViewState(
    val featuredPodcasts: PersistentList<PodcastWithExtraInfo> = persistentListOf(),
    val refreshing: Boolean = false,
    val selectedHomeCategory: HomeCategory = HomeCategory.Discover,
    val homeCategories: List<HomeCategory> = emptyList(),
    val filterableCategoriesModel: FilterableCategoriesModel = FilterableCategoriesModel(),
    val podcastCategoryFilterResult: PodcastCategoryFilterResult = PodcastCategoryFilterResult(),
    val libraryEpisodes: List<EpisodeToPodcast> = emptyList(),
    val errorMessage: String? = null
)
