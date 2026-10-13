package ke.geoai.agroweather.data

/** Quick-pick locations: major agricultural towns and county centres (approximate town-centre coordinates). */
data class KenyaPlace(val name: String, val county: String, val lat: Double, val lon: Double)

object KenyaPlaces {
    val all: List<KenyaPlace> = listOf(
        KenyaPlace("Eldoret", "Uasin Gishu", 0.5143, 35.2698),
        KenyaPlace("Kitale", "Trans Nzoia", 1.0157, 35.0062),
        KenyaPlace("Kapsabet", "Nandi", 0.2022, 35.1050),
        KenyaPlace("Kericho", "Kericho", -0.3689, 35.2863),
        KenyaPlace("Kakamega", "Kakamega", 0.2827, 34.7519),
        KenyaPlace("Kisumu", "Kisumu", -0.0917, 34.7680),
        KenyaPlace("Nakuru", "Nakuru", -0.3031, 36.0800),
        KenyaPlace("Narok", "Narok", -1.0783, 35.8600),
        KenyaPlace("Nyeri", "Nyeri", -0.4201, 36.9476),
        KenyaPlace("Meru", "Meru", 0.0480, 37.6559),
        KenyaPlace("Embu", "Embu", -0.5389, 37.4596),
        KenyaPlace("Machakos", "Machakos", -1.5177, 37.2634),
        KenyaPlace("Kitui", "Kitui", -1.3667, 38.0106),
        KenyaPlace("Kajiado", "Kajiado", -1.8524, 36.7820),
        KenyaPlace("Nairobi", "Nairobi", -1.2921, 36.8219),
        KenyaPlace("Mombasa", "Mombasa", -4.0435, 39.6682),
        KenyaPlace("Garissa", "Garissa", -0.4532, 39.6461),
    )
}
