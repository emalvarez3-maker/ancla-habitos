/*
 * Copyright (C) 2016-2025 Álinson Santos Xavier <git@axavier.org>
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * Loop Habit Tracker is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.isoron.uhabits.ancla.consciousness

/** One line Dexter says, then the silence after it, in seconds. */
data class Cue(val text: String, val pauseSeconds: Int)

/** A guided mindfulness practice: opening, a cycle repeated to fill the time, closing. */
enum class Meditation(val title: String, val purpose: String, val opening: List<Cue>, val cycle: List<Cue>, val closing: List<Cue>) {
    BREATHING(
        "Respiración", "Para calmarte",
        listOf(
            Cue("Busca una postura cómoda. Puedes cerrar los ojos o dejar la mirada suave hacia abajo.", 4),
            Cue("Vamos a respirar despacio, con la exhalación un poco más larga que la inhalación.", 3)
        ),
        listOf(
            Cue("Inhala por la nariz, contando hasta cuatro.", 4),
            Cue("Sostén un momento.", 4),
            Cue("Y suelta despacio, hasta seis.", 6),
            Cue("Otra vez. Inhala.", 4),
            Cue("Sostén.", 4),
            Cue("Suelta, dejando que los hombros bajen.", 7),
            Cue("Si la mente se va, está bien. Solo vuelve a la siguiente respiración.", 10)
        ),
        listOf(
            Cue("Deja que la respiración vuelva a su ritmo natural.", 6),
            Cue("Nota cómo estás ahora, sin cambiar nada.", 6),
            Cue("Cuando quieras, abre los ojos. Bien hecho.", 2)
        )
    ),
    BODY_SCAN(
        "Escaneo del cuerpo", "Para llegar a tu cuerpo",
        listOf(
            Cue("Acomódate, sentado o acostado. Deja que el cuerpo pese.", 5),
            Cue("Vamos a recorrer el cuerpo con la atención, sin cambiar nada, solo notando.", 4)
        ),
        listOf(
            Cue("Lleva la atención a los pies. Nota el contacto, la temperatura, cualquier sensación.", 15),
            Cue("Sube a las piernas y las rodillas. Si hay tensión, solo obsérvala.", 15),
            Cue("Ahora el abdomen. Siente cómo sube y baja con la respiración.", 15),
            Cue("El pecho y la espalda. Nota dónde hay espacio y dónde hay presión.", 15),
            Cue("Los hombros, los brazos, las manos. Deja que se aflojen un poco.", 15),
            Cue("El cuello, la mandíbula, la cara. Suelta el entrecejo.", 15),
            Cue("Ahora el cuerpo entero, como un solo campo de sensaciones.", 15)
        ),
        listOf(
            Cue("Agradece a tu cuerpo por llevarte a todas partes.", 5),
            Cue("Mueve suavemente los dedos, y vuelve cuando estés listo.", 2)
        )
    ),
    NOTING(
        "Notar pensamientos", "Para una mente ocupada",
        listOf(
            Cue("Siéntate cómodo y apoya la atención en la respiración.", 5),
            Cue("Cuando aparezca un pensamiento, ponle una etiqueta suave: pensando, planeando, recordando, preocupándome. Y vuelve a respirar.", 6)
        ),
        listOf(
            Cue("Respira y observa qué aparece.", 20),
            Cue("Si notas un pensamiento, nómbralo en silencio, sin seguirlo.", 20),
            Cue("No tienes que detener los pensamientos. Solo verlos pasar, como nubes.", 20),
            Cue("Vuelve a la respiración, una vez más, sin juzgarte.", 20)
        ),
        listOf(
            Cue("Nota el espacio que hay entre tú y tus pensamientos. Ese espacio también eres tú.", 6),
            Cue("Vuelve despacio. Gracias por darte este rato.", 2)
        )
    ),
    KINDNESS(
        "Bondad amorosa", "Para emociones difíciles",
        listOf(
            Cue("Ponte cómodo y lleva una mano al pecho, si quieres.", 5),
            Cue("Vamos a desear bien, empezando por ti. Repite en silencio cada frase.", 4)
        ),
        listOf(
            Cue("Que esté en paz. Que me trate con amabilidad.", 12),
            Cue("Que acepte este momento tal como es.", 12),
            Cue("Ahora piensa en alguien que quieres. Que esté bien. Que esté en paz.", 14),
            Cue("Piensa en alguien con quien tienes dificultades. Que también esté bien, a su manera.", 14),
            Cue("Y ahora todos los seres. Que estén en paz.", 14)
        ),
        listOf(
            Cue("Quédate un momento con lo que sientes ahora.", 6),
            Cue("Cuando quieras, abre los ojos.", 2)
        )
    );

    /** The whole practice for about [minutes]: the cycle repeated as many times as fit. */
    fun steps(minutes: Int): List<Cue> {
        val target = minutes.coerceIn(MIN_MINUTES, MAX_MINUTES) * 60
        fun length(list: List<Cue>) = list.sumOf { it.pauseSeconds + speakSeconds(it.text) }
        val fixed = length(opening) + length(closing)
        val round = length(cycle).coerceAtLeast(1)
        val rounds = ((target - fixed) / round).coerceAtLeast(1)
        return opening + List(rounds) { cycle }.flatten() + closing
    }

    companion object {
        const val MIN_MINUTES = 1
        const val MAX_MINUTES = 30

        /** Roughly how long Dexter takes to say [text], at a calm pace (about 2.3 words a second). */
        fun speakSeconds(text: String) = (text.split(Regex("\\s+")).count { it.isNotBlank() } / 2.3).toInt() + 1

        /** The practice a name or a mood points to ("respiración", "body_scan", "ansiedad"...). */
        fun of(kind: String?): Meditation {
            val k = java.text.Normalizer.normalize(kind.orEmpty().lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
            return when {
                "body" in k || "cuerpo" in k || "escaneo" in k -> BODY_SCAN
                "not" in k || "pensa" in k || "mente" in k -> NOTING
                "kind" in k || "bondad" in k || "amor" in k || "metta" in k || "compasion" in k -> KINDNESS
                else -> BREATHING
            }
        }
    }
}
