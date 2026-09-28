/*
 * Copyright (C) 2014 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.latin

import android.content.Context
import helium314.keyboard.latin.utils.Log
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Cache for dictionary facilitators of multiple locales.
 * This class automatically creates and releases up to 3 facilitator instances using LRU policy.
 */
class DictionaryFacilitatorLruCache(
    private val mContext: Context,
    private val mDictionaryNamePrefix: String
) {
    private val mLock = Any()
    private val mFacilitatorMap = LinkedHashMap<String, Pair<Locale, DictionaryFacilitator>>()
    private var mUseAppsDictionary = false

    fun setUseAppsDictionary(useAppsDictionary: Boolean) {
        synchronized(mLock) {
            if (mUseAppsDictionary == useAppsDictionary) return
            mUseAppsDictionary = useAppsDictionary
            mFacilitatorMap.values.forEach { (loc, fac) ->
                fac.resetDictionaries(
                    mContext, loc, mUseAppsDictionary,
                    false, false, mDictionaryNamePrefix, null
                )
                waitForLoadingMainDictionary(fac)
            }
        }
    }

    fun get(locale: Locale): DictionaryFacilitator {
        val key = locale.language
        val facilitator: DictionaryFacilitator
        synchronized(mLock) {
            val existing = mFacilitatorMap[key]
            if (existing != null) {
                mFacilitatorMap.remove(key)
                mFacilitatorMap[key] = existing
                facilitator = existing.second
            } else {
                if (mFacilitatorMap.size >= 4) {
                    val oldestKey = mFacilitatorMap.keys.first()
                    mFacilitatorMap.remove(oldestKey)?.second?.closeDictionaries()
                }
                val newFac = DictionaryFacilitatorProvider.getDictionaryFacilitator(true)
                newFac.resetDictionaries(
                    mContext, locale, mUseAppsDictionary,
                    false, false, mDictionaryNamePrefix, null
                )
                mFacilitatorMap[key] = locale to newFac
                facilitator = newFac
            }
        }
        waitForLoadingMainDictionary(facilitator)
        return facilitator
    }

    fun closeDictionaries() {
        synchronized(mLock) {
            mFacilitatorMap.values.forEach { (_, fac) -> fac.closeDictionaries() }
            mFacilitatorMap.clear()
        }
    }

    companion object {
        private const val TAG = "DictFacilitatorLruCache"
        private const val WAIT_FOR_LOADING_MAIN_DICT_IN_MILLISECONDS = 1000L
        private const val MAX_RETRY_COUNT_FOR_WAITING_FOR_LOADING_DICT = 5

        private fun waitForLoadingMainDictionary(dictionaryFacilitator: DictionaryFacilitator) {
            for (i in 0 until MAX_RETRY_COUNT_FOR_WAITING_FOR_LOADING_DICT) {
                try {
                    dictionaryFacilitator.waitForLoadingMainDictionaries(
                        WAIT_FOR_LOADING_MAIN_DICT_IN_MILLISECONDS, TimeUnit.MILLISECONDS
                    )
                    return
                } catch (e: InterruptedException) {
                    Log.i(TAG, "Interrupted during waiting for loading main dictionary.", e)
                    if (i < MAX_RETRY_COUNT_FOR_WAITING_FOR_LOADING_DICT - 1) {
                        Log.i(TAG, "Retry", e)
                    } else {
                        Log.w(TAG, "Give up retrying. Retried $MAX_RETRY_COUNT_FOR_WAITING_FOR_LOADING_DICT times.", e)
                    }
                }
            }
        }
    }
}
