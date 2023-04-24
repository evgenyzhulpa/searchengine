package searchengine.services;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import searchengine.model.LemmaRepository;

@Service
@RequiredArgsConstructor
public class LemmaService {
    @Autowired
    private LemmaRepository lemmaRepository;
}
