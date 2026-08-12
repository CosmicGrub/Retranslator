package com.retroid.translator.wear

import android.app.Application

/**
 * Mirrors the phone app's [com.retroid.translator.TranslatorApp] role (a
 * plain Application subclass, no shared engines eagerly created here yet -
 * unlike the phone app, which lazily builds one shared EspeakEngine on
 * first access). Kept intentionally minimal for this pass: engines are
 * constructed directly by MainActivity/TranslateViewModel, not hung off
 * this class, since :wear has exactly one screen so far.
 */
class WearTranslatorApp : Application()
