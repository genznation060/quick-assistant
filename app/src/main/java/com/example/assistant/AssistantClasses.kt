class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val button = Button(this).apply {
            text = "Set as default assistant"
            setOnClickListener {
                startActivity(Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS))
            }
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            addView(button)
        }
        setContentView(layout)
    }
}
