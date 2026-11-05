package com.koala.koackpit

import android.content.Context
import android.widget.Toast
import androidx.compose.ui.graphics.Color

val Navy = Color(0xFF00071A)
val Card = Color(0xFF0E1A33)
val CardHi = Color(0xFF16264A)
val Yellow = Color(0xFFFFCC00)
val Red = Color(0xFFE63946)
val Muted = Color(0xFF8A96B0)

fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
