package com.example.restjava.controllers;

import com.example.restjava.database.DbEntity;
import com.example.restjava.database.Repository;
import com.example.restjava.entity.*;
import com.example.restjava.exceptions.ServerException;
import com.example.restjava.memory.InMemoryStorage;
import com.example.restjava.services.AgregateService;
import com.example.restjava.services.CounterService;
import com.example.restjava.services.MathService;
import com.example.restjava.validators.ErrorList;
import com.example.restjava.validators.NumbersValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@RestController
public class ResultPairController {
    private static Logger logger = LoggerFactory.getLogger(ResultPairController.class);
    private MathService mathService;                                            // основной сервис
    private NumbersValidator validator;                                         // валидатор
    private CounterService counterService;                                      // счетчик
    private InMemoryStorage inMemoryStorage;                                    // кэш
    private AgregateService agregateService;                                    // агрегирующий сервис
    private Repository repository;                                              // БД

    @Autowired
    public ResultPairController(MathService service, NumbersValidator validator,
                                CounterService counterService, InMemoryStorage inMemoryStorage,
                                AgregateService agregateService, Repository repository){
        this.mathService = service;
        this.validator = validator;
        this.counterService = counterService;
        this.inMemoryStorage = inMemoryStorage;
        this.agregateService = agregateService;
        this.repository = repository;
    }

    @GetMapping("/getresultpair")
    @ResponseStatus(HttpStatus.OK)
    public ResponseEntity<Object> getResultPair(int[] nums) {
       Numbers numbers = new Numbers(nums);
        ErrorList errors = validator.Validate((numbers));                          // получение ошибок валидации
        if(!errors.isEmpty()) {                                                 // есть ошибки валидации
            errors.setStatus(HttpStatus.BAD_REQUEST.name());                        // установка статуса
            logger.error("Parameter is not valid");                                 // логирование ошибки
            return new ResponseEntity<>(errors, HttpStatus.BAD_REQUEST);
        }

        try{
            counterService.incrementSynchronizedCount();                        // инкрементирование счетчиков
            counterService.incrementUnsynchronizedCount();

            ResultPair resultPair = mathService.getResult(numbers);             //получение результата

            // асинхронное сохранение результатов в кэше и БД
            CompletableFuture.runAsync(()->inMemoryStorage.add(numbers, resultPair));
            CompletableFuture.runAsync(()->repository.save(numbers, resultPair));

            return ResponseEntity.ok(resultPair);
        } catch(ServerException exc){                                           //ловим ошибку сервера
            logger.error(exc.getMessage());                                     //логгирование
            errors.add(exc.getMessage());                                       //+ 1 ошибка
            errors.setStatus(HttpStatus.BAD_REQUEST.name());                    //установка статуса
            return new ResponseEntity<>(errors, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @PostMapping("/getresultpairs")
    @ResponseStatus(HttpStatus.OK)
    @ResponseBody
    public ResponseEntity<Object> getResultPairs(@ModelAttribute("parameters") List<BulkParameter> parameters){
        ErrorList errors = new ErrorList();                                     // все ошибки валидации
        parameters = parameters.stream().filter(p->{
            ErrorList tempErrors = validator.Validate(p.toNumbers());               // проверка валидности параметра
            if(!tempErrors.isEmpty()) errors.add(tempErrors);                       // ошибки есть
            return tempErrors.isEmpty();
        }).collect(Collectors.toList());

        Map<Numbers, ResultPair> resultMap = new HashMap<>();                   // map входных и выходных значений
        parameters.forEach(p -> {                                               // проход по списку параметров
            Numbers numbers = p.toNumbers();                                        // создание входного объекта

            counterService.incrementSynchronizedCount();                            // инкрементирование счетчиков
            counterService.incrementUnsynchronizedCount();

            ResultPair resultPair = mathService.getResult(numbers);                 // создание выходного объекта
            resultMap.put(numbers, resultPair);                                     // заносим вх. и вых. объекты в мэп
            CompletableFuture.runAsync(() -> repository.save(numbers, resultPair)); // асинхронное сохранение в БД
        });

        // асинхронный подсчет агрегирующих значений
        AgregateValues agregateValues = new AgregateValues();
        agregateService.getAgregateValues(resultMap.values(), agregateValues);

        // добавление в кэш
        resultMap.entrySet().stream().forEach(
                e -> inMemoryStorage.add(e.getKey(), e.getValue()));
        return ResponseEntity.ok(new BulkResultObject(resultMap.values(), errors, agregateValues));
    }




    @GetMapping("/getcash")
    @ResponseStatus(HttpStatus.OK)
    public ResponseEntity<Map<Numbers, ResultPair>> getCash(){          // получение кэша
        return ResponseEntity.ok(inMemoryStorage.getMap());
    }

    @GetMapping("/getdb")
    @ResponseStatus(HttpStatus.OK)
    public ResponseEntity<List<DbEntity>> getDb(){                      // получение данных из БД
        return ResponseEntity.ok(repository.getAll());
    }



    @GetMapping("/resetcounters")
    public ResponseEntity<Counters> resetCounters(){                    // сброс счетчиков
        counterService.resetCounters();
        return getCounters();
    }

    @GetMapping( "/getcounters")
    @ResponseBody
    public ResponseEntity<Counters> getCounters(){                      // получение счетчиков
        return ResponseEntity.ok(counterService.getCounters());
    }
}



/*        parameters.stream().filter(p -> {                                       // фильтрация списка параметров
            ErrorList tempErrors = validator.Validate(p.toNumbers());               // проверка валидности параметра
            if(!tempErrors.isEmpty()) errors.add(tempErrors);                       // ошибки есть
            return tempErrors.isEmpty();
        });
*/
