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

package org.isoron.uhabits.ancla

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.isoron.uhabits.R
import org.isoron.uhabits.ancla.assistant.DexterAnnouncer
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** A line of wisdom and who said it. */
data class Saying(val text: String, val author: String)

/**
 * The wisdom of the day: one saying a day from classic thinkers and traditions (all long in the
 * public domain, attributed to their source), the same all day, shown in Hoy, known to Dexter and
 * said out loud every morning. Real quotations, never invented by the AI.
 */
object Wisdom {
    val SAYINGS = listOf(
        Saying("No es que tengamos poco tiempo, sino que perdemos mucho.", "Séneca"),
        Saying("Sufrimos más a menudo en la imaginación que en la realidad.", "Séneca"),
        Saying("Mientras esperamos vivir, la vida pasa.", "Séneca"),
        Saying("La suerte es lo que sucede cuando la preparación se encuentra con la oportunidad.", "Séneca (atribuido)"),
        Saying("Tienes poder sobre tu mente, no sobre los acontecimientos. Date cuenta de esto y encontrarás la fuerza.", "Marco Aurelio"),
        Saying("La felicidad de tu vida depende de la calidad de tus pensamientos.", "Marco Aurelio"),
        Saying("Lo que se interpone en el camino se convierte en el camino.", "Marco Aurelio"),
        Saying("No pierdas más tiempo discutiendo cómo debe ser un buen hombre. Sé uno.", "Marco Aurelio"),
        Saying("Muy poco se necesita para hacer una vida feliz; todo está dentro de ti, en tu forma de pensar.", "Marco Aurelio"),
        Saying("No nos perturban las cosas, sino las opiniones que tenemos de ellas.", "Epicteto"),
        Saying("Primero di lo que quieres ser; luego haz lo que tienes que hacer.", "Epicteto"),
        Saying("Nadie es libre si no es dueño de sí mismo.", "Epicteto"),
        Saying("Una vida sin examen no merece ser vivida.", "Sócrates, en la Apología de Platón"),
        Saying("Conócete a ti mismo.", "Inscripción del templo de Delfos"),
        Saying("Somos lo que hacemos repetidamente. La excelencia, entonces, no es un acto sino un hábito.", "Will Durant, resumiendo a Aristóteles"),
        Saying("Nadie se baña dos veces en el mismo río.", "Heráclito"),
        Saying("El carácter de un hombre es su destino.", "Heráclito"),
        Saying("No importa lo despacio que vayas, siempre que no te detengas.", "Confucio (atribuido)"),
        Saying("Saber lo que se sabe y saber lo que no se sabe: eso es el verdadero conocimiento.", "Confucio, Analectas"),
        Saying("Un viaje de mil millas comienza con un solo paso.", "Lao Tse, Tao Te Ching"),
        Saying("Conocer a los demás es inteligencia; conocerse a uno mismo es sabiduría.", "Lao Tse, Tao Te Ching"),
        Saying("Quien sabe que tiene suficiente es rico.", "Lao Tse, Tao Te Ching"),
        Saying("Somos lo que pensamos. Todo lo que somos surge con nuestros pensamientos.", "Dhammapada"),
        Saying("Mejor que mil palabras vacías es una sola que traiga paz.", "Dhammapada"),
        Saying("Aunque alguien venza mil veces a mil hombres en batalla, el mayor vencedor es quien se vence a sí mismo.", "Dhammapada"),
        Saying("Tienes derecho a la acción, pero no a sus frutos.", "Bhagavad Gita"),
        Saying("Ayer era listo y quería cambiar el mundo. Hoy soy sabio y me estoy cambiando a mí mismo.", "Rumi (atribuido)"),
        Saying("La herida es el lugar por donde entra la luz.", "Rumi (atribuido)"),
        Saying("Lo que buscas te está buscando.", "Rumi (atribuido)"),
        Saying("Todo lo que es profundo ama la máscara.", "Friedrich Nietzsche"),
        Saying("Quien tiene un porqué para vivir puede soportar casi cualquier cómo.", "Friedrich Nietzsche"),
        Saying("Llega a ser el que eres.", "Píndaro"),
        Saying("El corazón tiene razones que la razón no conoce.", "Blaise Pascal"),
        Saying("Toda la desdicha de los hombres viene de no saber quedarse tranquilos en una habitación.", "Blaise Pascal"),
        Saying("Mi vida ha estado llena de desgracias terribles, la mayoría de las cuales nunca ocurrieron.", "Michel de Montaigne"),
        Saying("La mayor cosa del mundo es saber pertenecerse a sí mismo.", "Michel de Montaigne"),
        Saying("Lo que puedas hacer, o sueñes que puedes hacer, comiénzalo. La audacia tiene genio, poder y magia.", "Goethe (atribuido)"),
        Saying("Actúa de tal modo que trates a la humanidad siempre como un fin y nunca solo como un medio.", "Immanuel Kant"),
        Saying("Lo que no me mata me hace más fuerte.", "Friedrich Nietzsche"),
        Saying("El que mira hacia fuera, sueña; el que mira hacia dentro, despierta.", "Carl Jung"),
        Saying("Lo que niegas te somete; lo que aceptas te transforma.", "Carl Jung (atribuido)"),
        Saying("La vida solo puede ser comprendida hacia atrás, pero debe ser vivida hacia delante.", "Søren Kierkegaard"),
        Saying("La ansiedad es el vértigo de la libertad.", "Søren Kierkegaard"),
        Saying("Hay que cultivar nuestro jardín.", "Voltaire, Cándido"),
        Saying("Ve con confianza en la dirección de tus sueños. Vive la vida que has imaginado.", "Henry David Thoreau"),
        Saying("Fui a los bosques porque quería vivir deliberadamente.", "Henry David Thoreau, Walden"),
        Saying("Lo que está detrás de nosotros y lo que está delante son poca cosa comparado con lo que hay dentro.", "Ralph Waldo Emerson (atribuido)"),
        Saying("Cada mañana nacemos de nuevo. Lo que hacemos hoy es lo que más importa.", "Atribuido a Buda"),
        Saying("Caminante, no hay camino, se hace camino al andar.", "Antonio Machado"),
        Saying("Nada te turbe, nada te espante; todo se pasa.", "Santa Teresa de Jesús"),
        Saying("En el atardecer de la vida seremos examinados en el amor.", "San Juan de la Cruz"),
        Saying("Nuestro corazón está inquieto hasta que descansa en ti.", "San Agustín, Confesiones"),
        Saying("El hombre es la medida de todas las cosas.", "Protágoras"),
        Saying("La paciencia es amarga, pero su fruto es dulce.", "Jean-Jacques Rousseau (atribuido)"),
        Saying("Quien domina a los otros es fuerte; quien se domina a sí mismo es poderoso.", "Lao Tse, Tao Te Ching"),
        Saying("Entre el estímulo y la respuesta hay un espacio. En ese espacio está nuestra libertad.", "Atribuido a Viktor Frankl")
    )

    /** Today's saying: the same all day, a different one each day, in an order that doesn't repeat for weeks. */
    fun of(day: LocalDate = LocalDate.now()): Saying {
        val n = SAYINGS.size
        // A step coprime with n walks every saying once before repeating, jumping around the list.
        val step = (17 until 17 + n).first { gcd(it, n) == 1 }
        val i = Math.floorMod(day.toEpochDay() * step, n.toLong()).toInt()
        return SAYINGS[i]
    }

    private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

    // --- Every morning, out loud ---

    private const val CHANNEL = "DEXTER_WISDOM"
    private const val HOUR = 8
    private const val ACTION = "ec.ancla.habitos.ACTION_WISDOM"

    private fun pending(context: Context) = PendingIntent.getBroadcast(
        context, 0x515D, Intent(context, WisdomReceiver::class.java).setAction(ACTION), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /** Schedules tomorrow's (or today's, before 8:00) saying. Idempotent. */
    fun schedule(context: Context) {
        val now = LocalDateTime.now()
        var at = now.toLocalDate().atTime(HOUR, 0)
        if (!at.isAfter(now)) at = at.plusDays(1)
        val millis = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        runCatching {
            context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pending(context))
        }.onFailure { Log.w("Wisdom", "could not schedule: ${it.javaClass.simpleName}") }
    }

    fun deliver(context: Context) {
        val s = of()
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.wisdom_channel), NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            context, 0x515E, Intent(context, TodayActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.wisdom_title))
            .setContentText("«${s.text}» — ${s.author}")
            .setStyle(NotificationCompat.BigTextStyle().bigText("«${s.text}»\n— ${s.author}"))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(0x515D, n) }
        DexterAnnouncer.announce(context, context.getString(R.string.wisdom_spoken, s.text, s.author))
    }
}

class WisdomReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Wisdom.deliver(context)
        Wisdom.schedule(context)
    }
}
