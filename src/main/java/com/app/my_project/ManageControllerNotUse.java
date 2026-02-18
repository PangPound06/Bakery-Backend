package com.app.my_project;
/* 
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.app.models.CartModel;

@RestController
@RequestMapping("/api/books")
public class ManageControllerNotUse {
    private List<CartModel> books = new ArrayList<>(Arrays.asList(
        
    ));

    @GetMapping
    public List<CartModel> getAllBooks() {
        return books;
    }

    @GetMapping("/{id}")
    public CartModel getBookById(@PathVariable String id) {
        return books.stream()
                    .filter(book -> book.getId().equals(id))
                    .findFirst()
                    .orElse(null);
    }

    @PostMapping
    public CartModel addBook(@RequestBody CartModel book) {
        books.add(book);
        return book;
    }

    @PutMapping("/{id}")
    public CartModel updateBook(@PathVariable String id, @RequestBody CartModel updatedBook) {
        for (int i = 0; i < books.size(); i++) {
            if (books.get(i).getId().equals(id)) {
                books.set(i, updatedBook);
                return updatedBook;
            }
        }
        return null;
    }

    @DeleteMapping("/{id}")
    public String deleteBook(@PathVariable String id) {
        books.removeIf(book -> book.getId().equals(id));
        return "Book with id " + id + " deleted.";
    }
}
*/