package tech.idct.clipra.core.data.di

import javax.inject.Qualifier

/** A scope that lives as long as the app, for work that must finish after a screen closes. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
