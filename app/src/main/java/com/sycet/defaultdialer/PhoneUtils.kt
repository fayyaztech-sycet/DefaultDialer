import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.NumberParseException

object PhoneUtils {
    fun normalizePhone(phone: String, region: String = "IN"): String {
        val util = PhoneNumberUtil.getInstance()
        return try {
            val numberProto = util.parse(phone, region)
            util.format(numberProto, PhoneNumberUtil.PhoneNumberFormat.E164) // +918552886242
        } catch (e: NumberParseException) {
            phone.filter { it.isDigit() } // fallback
        }
    }
}