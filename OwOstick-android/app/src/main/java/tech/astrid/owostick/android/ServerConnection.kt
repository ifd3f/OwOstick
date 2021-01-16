package tech.astrid.owostick.android

import com.beust.klaxon.Klaxon
import com.beust.klaxon.TypeAdapter
import com.beust.klaxon.TypeFor
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.kotlin.Observables.combineLatest
import io.reactivex.rxjava3.subjects.BehaviorSubject
import io.reactivex.rxjava3.subjects.PublishSubject
import io.reactivex.rxjava3.subjects.Subject
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import kotlin.reflect.KClass

class ServerConnection(val uri: URI, val password: String) : WebSocketClient(uri) {
    private val klaxon = Klaxon()
    private val messages: Subject<Event> = PublishSubject.create()
    val power: Observable<Float>
        get() = messages
            .filter { it is Event.SetPower }
            .map { (it as Event.SetPower).value }

    private val _state = BehaviorSubject.createDefault<State>(State.Disconnected)
    val state get(): Observable<State> = _state

    init {
        combineLatest(
            messages.filter { it is Event.Authentication },
            state.filter { it is State.Unauthenticated }
        )
            .subscribe { (ev, _) ->
                ev as Event.Authentication
                if (ev.success)
                    _state.onNext(State.Authenticated)
                else
                    close()
            }
    }

    private fun send(action: Action) {
        send(klaxon.toJsonString(action))
    }

    override fun onOpen(handshakedata: ServerHandshake?) {
        _state.onNext(State.Unauthenticated)
        send(Action.Authenticate(password))
    }

    override fun onMessage(message: String?) {
        messages.onNext(klaxon.parse<Event>(message!!))
    }

    override fun onClose(code: Int, reason: String?, remote: Boolean) {
        _state.onNext(State.Disconnected)
    }

    override fun onError(ex: Exception?) {
    }

    sealed class State {
        object Disconnected : State()
        object Unauthenticated : State()
        object Authenticated : State()
    }

    @TypeFor(field = "type", adapter = Event.Adapter::class)
    sealed class Event {
        data class SetPower(val value: Float) : Event()
        data class Authentication(val success: Boolean) : Event()

        class Adapter : TypeAdapter<Event> {
            override fun classFor(type: Any): KClass<out Event> = when (type) {
                "set_power" -> SetPower::class
                "authentication" -> Authentication::class
                else -> throw IllegalArgumentException("Unknown type $type")
            }
        }
    }

    @TypeFor(field = "type", adapter = Action.Adapter::class)
    sealed class Action {
        data class Authenticate(val value: String) : Action()

        class Adapter : TypeAdapter<Action> {
            override fun classFor(type: Any): KClass<out Action> = when (type) {
                "authenticate" -> Authenticate::class
                else -> throw IllegalArgumentException("Unknown type $type")
            }
        }
    }
}