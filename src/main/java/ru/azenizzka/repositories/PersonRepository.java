package ru.azenizzka.repositories;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import ru.azenizzka.entities.Person;

public interface PersonRepository extends JpaRepository<Person, Integer> {
  boolean existsByChatId(String chatId);

  Person findByChatId(String chatId);

  Person findByUsername(String username);

  @Query("SELECT DISTINCT p.groupNum FROM Person p WHERE p.groupNum <> 0")
  List<Integer> findAllGroupNums();
}
