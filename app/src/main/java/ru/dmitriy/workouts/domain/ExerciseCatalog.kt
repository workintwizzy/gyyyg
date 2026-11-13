package ru.dmitriy.workouts.domain

/** Short original Russian instructions, reviewed against the linked professional libraries. */
object ExerciseCatalog {
    val sources = listOf(
        ExerciseSource("nasm-leg-press", "NASM · Leg Press", "https://www.nasm.org/resource-center/exercise-library/leg-press"),
        ExerciseSource("ace-chest", "ACE · Seated Chest Press", "https://www.acefitness.org/resources/everyone/exercise-library/188/seated-chest-press/"),
        ExerciseSource("mayo-lat", "Mayo Clinic · Lat pull-down", "https://www.mayoclinic.org/healthy-lifestyle/fitness/multimedia/lat-pull-down/vid-20084683"),
        ExerciseSource("mayo-curl", "Mayo Clinic · Seated hamstring curl", "https://www.mayoclinic.org/healthy-lifestyle/fitness/multimedia/seated-hamstring-curl/vid-20084685"),
        ExerciseSource("mayo-row", "Mayo Clinic · Seated row", "https://www.mayoclinic.org/healthy-lifestyle/fitness/multimedia/seated-row/vid-20084688"),
        ExerciseSource("ace-lateral", "ACE · Lateral Raise", "https://www.acefitness.org/resources/everyone/exercise-library/26/lateral-raise/"),
        ExerciseSource("nasm-deadbug", "NASM · Dead Bug", "https://www.nasm.org/resource-center/exercise-library/dead-bug"),
        ExerciseSource("nasm-lyingcurl", "NASM · Lying Leg Curl", "https://www.nasm.org/resource-center/exercise-library/lying-leg-curl"),
        ExerciseSource("nasm-incline", "NASM · Incline Push-Up", "https://www.nasm.org/resource-center/exercise-library/incline-push-up")
    )
    private fun t(setup:String,start:String,steps:List<String>,breathing:String,mistakes:List<String>,source:String) = Technique(setup,start,steps,breathing,mistakes,listOf(source))
    val exercises = listOf(
        Exercise("leg-press","Жим ногами в тренажёре","Бёдра и ягодицы","Жим ногами",technique=t("Настройте сиденье: таз, поясница и голова опираются на спинку. Узнайте у сотрудника, как устроены стопоры.","Вся стопа на платформе; стопы примерно на ширине таза.",listOf("Плавно выжмите платформу; колени движутся по направлению носков.","Остановитесь до резкого выпрямления коленей.","Верните платформу настолько, насколько сохраняется опора таза и поясницы."),"Выдох при жиме, вдох при возвращении.",listOf("Отрыв таза или пяток","Сведение коленей внутрь","Рывки и чрезмерный вес"),"nasm-leg-press")),
        Exercise("chest-press","Жим от груди сидя","Грудь, плечи, трицепс","Грудной жим",technique=t("Высота сиденья: рукояти около середины груди, не позади неё.","Стопы устойчивы, спина на опоре, кисти прямые, обхват полный.",listOf("Плавно выжмите рукояти вперёд.","Вернитесь, сохраняя устойчивый корпус и спокойное положение плеч."),"Выдох вперёд, вдох назад.",listOf("Прогиб с отрывом корпуса","Заломленные кисти","Резкий возврат веса"),"ace-chest"),alternatives=listOf("incline-pushup")),
        Exercise("lat-pulldown","Тяга верхнего блока к груди","Широчайшие и руки","Верхний блок",technique=t("Подгоните валик: он удерживает бёдра. Выберите удобный хват.","Стопы на полу, корпус устойчив с небольшим отклонением назад.",listOf("Направьте локти вниз и опустите рукоять перед лицом к верхней части груди.","Плавно отпустите рукоять вверх без раскачивания."),"Выдох при тяге, вдох при возвращении.",listOf("Тяга за голову","Рывок корпусом","Чрезмерное отклонение назад"),"mayo-lat")),
        Exercise("seated-curl","Сгибание ног сидя","Задняя поверхность бедра","Сгибание ног",technique=t("Совместите колени с осью тренажёра. Нижний валик — под нижней частью голеней; верхний фиксирует бёдра.","Спина и таз на опоре, держитесь за рукояти.",listOf("Плавно согните колени, подводя валик под сиденье.","Контролируемо вернитесь без удара грузов."),"Выдох при сгибании, вдох при возврате.",listOf("Отрыв таза","Несовпадение колена с осью","Рывки"),"mayo-curl"),alternatives=listOf("lying-curl")),
        Exercise("supported-row","Горизонтальная тяга с опорой грудью","Спина и руки","Тяга с грудной опорой",technique=t("Настройте сиденье и грудной упор так, чтобы рукояти были доступны без вытягивания корпуса.","Грудь на опоре, стопы устойчивы, руки вытянуты без округления поясницы.",listOf("Тяните локти назад, позволяя лопаткам естественно двигаться.","Верните рукояти, сохраняя грудь на опоре."),"Выдох к корпусу, вдох от корпуса.",listOf("Отрыв груди и рывок поясницей","Поднятые к ушам плечи","Удар грузов"),"mayo-row"),alternatives=listOf("cable-row")),
        Exercise("lateral-raise","Подъём гантелей в стороны","Средняя часть плеч","Гантели","dumbbell_each",t("Возьмите лёгкие гантели: в журнале указывается масса одной.","Стойте устойчиво; локти немного согнуты и сохраняют угол.",listOf("Поднимите руки в стороны примерно до уровня плеч без раскачивания.","Опустите плавно. Не поворачивайте большие пальцы вниз."),"Выдох вверх, вдох вниз.",listOf("Рывок корпусом","Подъём плеч к ушам","Слишком тяжёлые гантели"),"ace-lateral")),
        Exercise("dead-bug","«Мёртвый жук»","Мышцы корпуса","Коврик","reps_per_side",t("Лягте на спину на коврик.","Руки вверх, бёдра и колени согнуты примерно под прямым углом; удерживайте положение поясницы.",listOf("Медленно отведите противоположные руку и ногу.","Остановитесь до потери контроля поясницы, вернитесь и поменяйте сторону.","Один повтор в журнале — на каждую сторону."),"Дышите свободно, не задерживая дыхание.",listOf("Прогиб в пояснице","Слишком большая амплитуда","Быстрые движения"),"nasm-deadbug")),
        Exercise("cable-row","Горизонтальная тяга блока сидя","Спина и руки","Нижний блок",technique=t("Установите рукоять и опору стоп. Это отдельное оборудование с собственным весом.","Сядьте устойчиво, колени немного согнуты, спина в естественном положении.",listOf("Подтяните рукоять к талии, ведя локти назад.","Плавно вытяните руки без раскачивания корпуса."),"Выдох при тяге, вдох при отпускании.",listOf("Рывок поясницей","Округление спины","Поднятые плечи"),"mayo-row"),alternatives=listOf("supported-row")),
        Exercise("lying-curl","Сгибание ног лёжа","Задняя поверхность бедра","Сгибание ног лёжа",technique=t("Совместите колени с осью тренажёра; валик расположите над пятками.","Лягте животом на опору, таз сохраняет контакт, держитесь за ручки.",listOf("Согните ноги плавно, не отрывая таз.","Опустите валик подконтрольно."),"Выдох при сгибании, вдох при возврате.",listOf("Подъём таза","Чрезмерный прогиб","Рывки"),"nasm-lyingcurl"),alternatives=listOf("seated-curl")),
        Exercise("incline-pushup","Отжимание от высокой опоры","Грудь, плечи, трицепс","Устойчивая высокая опора","bodyweight_reps",t("Выберите неподвижную опору достаточной высоты; чем выше, тем легче.","Ладони на опоре, тело одной линией, корпус напряжён.",listOf("Плавно приблизьте грудь к опоре, сгибая локти без разведения строго в стороны.","Оттолкнитесь, удерживая линию корпуса."),"Вдох вниз, выдох вверх.",listOf("Провисание поясницы","Неустойчивая опора","Рывок головой"),"nasm-incline"),alternatives=listOf("chest-press"))
    )
    val equipment = exercises.map { EquipmentProfile("eq-${it.id}",it.id,it.equipment,stepGrams=if(it.tracking=="dumbbell_each") 1000 else 2500) }
    fun initialProgram() = Program(targets=listOf(
        ExerciseTarget("leg-press","eq-leg-press",1,10,15,150),
        ExerciseTarget("chest-press","eq-chest-press",1,8,12,120),
        ExerciseTarget("lat-pulldown","eq-lat-pulldown",1,8,12,120),
        ExerciseTarget("seated-curl","eq-seated-curl",1,10,15,90),
        ExerciseTarget("supported-row","eq-supported-row",1,10,12,120),
        ExerciseTarget("lateral-raise","eq-lateral-raise",1,12,15,90),
        ExerciseTarget("dead-bug","eq-dead-bug",1,6,8,60)))
    val warmup = "5–7 минут лёгкой ходьбы или велотренажёра, затем лёгкие разминочные подходы. Они записываются отдельно и не входят в рабочий объём."
    val rirExplanation = "В запасе — сколько повторений вы ещё могли бы выполнить с хорошей техникой. В первые недели оставляйте 3–4, затем примерно 2–3. Начальный вес подбирается на месте; рост и масса тела его не определяют."
}
