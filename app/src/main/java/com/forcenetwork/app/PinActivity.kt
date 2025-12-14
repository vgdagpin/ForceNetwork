package com.forcenetwork.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.forcenetwork.app.databinding.ActivityPinBinding
import com.forcenetwork.app.util.PreferencesManager

/**
 * Activity for PIN entry, setup, and verification.
 * Supports three modes: VERIFY, SETUP, and CHANGE.
 */
class PinActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPinBinding
    private lateinit var preferencesManager: PreferencesManager

    private var currentPin = StringBuilder()
    private var confirmPin: String? = null
    private var mode: PinMode = PinMode.VERIFY
    private var stage: PinStage = PinStage.ENTER

    private val pinDots: List<View> by lazy {
        listOf(binding.dot1, binding.dot2, binding.dot3, binding.dot4)
    }

    companion object {
        const val EXTRA_MODE = "pin_mode"
        const val RESULT_PIN_VERIFIED = 100
        const val RESULT_PIN_SET = 101

        const val MODE_VERIFY = "verify"
        const val MODE_SETUP = "setup"
        const val MODE_CHANGE = "change"
    }

    enum class PinMode {
        VERIFY, SETUP, CHANGE
    }

    enum class PinStage {
        ENTER, CONFIRM, OLD_PIN
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPinBinding.inflate(layoutInflater)
        setContentView(binding.root)

        preferencesManager = PreferencesManager.getInstance(this)

        setupMode()
        setupToolbar()
        setupNumberPad()
        updateUI()
    }

    private fun setupMode() {
        val modeString = intent.getStringExtra(EXTRA_MODE) ?: MODE_VERIFY
        mode = when (modeString) {
            MODE_SETUP -> PinMode.SETUP
            MODE_CHANGE -> PinMode.CHANGE
            else -> PinMode.VERIFY
        }

        // For change mode, start with old PIN verification
        stage = if (mode == PinMode.CHANGE) PinStage.OLD_PIN else PinStage.ENTER
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    private fun setupNumberPad() {
        val numberButtons = listOf(
            binding.btn0, binding.btn1, binding.btn2, binding.btn3, binding.btn4,
            binding.btn5, binding.btn6, binding.btn7, binding.btn8, binding.btn9
        )

        numberButtons.forEachIndexed { index, button ->
            val number = if (index == 0) "0" else index.toString()
            button.setOnClickListener { onNumberPressed(number) }
        }

        binding.btnDelete.setOnClickListener { onDeletePressed() }
        binding.btnCancel.setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    private fun onNumberPressed(number: String) {
        if (currentPin.length < 4) {
            currentPin.append(number)
            updatePinDots()

            if (currentPin.length == 4) {
                processPin()
            }
        }
    }

    private fun onDeletePressed() {
        if (currentPin.isNotEmpty()) {
            currentPin.deleteCharAt(currentPin.length - 1)
            updatePinDots()
        }
        hideError()
    }

    private fun updatePinDots() {
        pinDots.forEachIndexed { index, dot ->
            val backgroundRes = if (index < currentPin.length) {
                R.drawable.pin_dot_filled
            } else {
                R.drawable.pin_dot_empty
            }
            dot.setBackgroundResource(backgroundRes)
        }
    }

    private fun processPin() {
        val enteredPin = currentPin.toString()

        when (mode) {
            PinMode.VERIFY -> verifyPin(enteredPin)
            PinMode.SETUP -> handleSetupPin(enteredPin)
            PinMode.CHANGE -> handleChangePin(enteredPin)
        }
    }

    private fun verifyPin(pin: String) {
        if (preferencesManager.verifyPin(pin)) {
            setResult(RESULT_PIN_VERIFIED)
            finish()
        } else {
            showError(getString(R.string.pin_incorrect))
            resetPin()
        }
    }

    private fun handleSetupPin(pin: String) {
        when (stage) {
            PinStage.ENTER -> {
                confirmPin = pin
                stage = PinStage.CONFIRM
                updateUI()
                resetPin()
            }
            PinStage.CONFIRM -> {
                if (pin == confirmPin) {
                    preferencesManager.setPin(pin)
                    Toast.makeText(this, R.string.pin_set_success, Toast.LENGTH_SHORT).show()
                    setResult(RESULT_PIN_SET)
                    finish()
                } else {
                    showError(getString(R.string.pin_mismatch))
                    stage = PinStage.ENTER
                    confirmPin = null
                    updateUI()
                    resetPin()
                }
            }
            else -> {}
        }
    }

    private fun handleChangePin(pin: String) {
        when (stage) {
            PinStage.OLD_PIN -> {
                if (preferencesManager.verifyPin(pin)) {
                    stage = PinStage.ENTER
                    updateUI()
                    resetPin()
                } else {
                    showError(getString(R.string.pin_incorrect))
                    resetPin()
                }
            }
            PinStage.ENTER -> {
                confirmPin = pin
                stage = PinStage.CONFIRM
                updateUI()
                resetPin()
            }
            PinStage.CONFIRM -> {
                if (pin == confirmPin) {
                    preferencesManager.setPin(pin)
                    Toast.makeText(this, R.string.pin_set_success, Toast.LENGTH_SHORT).show()
                    setResult(RESULT_PIN_SET)
                    finish()
                } else {
                    showError(getString(R.string.pin_mismatch))
                    stage = PinStage.ENTER
                    confirmPin = null
                    updateUI()
                    resetPin()
                }
            }
        }
    }

    private fun updateUI() {
        val (title, subtitle) = when {
            mode == PinMode.CHANGE && stage == PinStage.OLD_PIN -> {
                "Enter Current PIN" to "Verify your current PIN"
            }
            mode == PinMode.SETUP && stage == PinStage.ENTER -> {
                getString(R.string.set_pin) to "Create a 4-digit PIN"
            }
            mode == PinMode.CHANGE && stage == PinStage.ENTER -> {
                "New PIN" to "Enter your new 4-digit PIN"
            }
            stage == PinStage.CONFIRM -> {
                getString(R.string.confirm_pin) to "Re-enter your PIN"
            }
            else -> {
                getString(R.string.enter_pin) to getString(R.string.pin_required)
            }
        }

        binding.tvPinTitle.text = title
        binding.tvPinSubtitle.text = subtitle
        binding.toolbar.title = when (mode) {
            PinMode.VERIFY -> getString(R.string.enter_pin)
            PinMode.SETUP -> getString(R.string.set_pin)
            PinMode.CHANGE -> getString(R.string.change_pin)
        }
    }

    private fun resetPin() {
        currentPin.clear()
        updatePinDots()
    }

    private fun showError(message: String) {
        binding.tvError.text = message
        binding.tvError.visibility = View.VISIBLE
    }

    private fun hideError() {
        binding.tvError.visibility = View.INVISIBLE
    }
}
