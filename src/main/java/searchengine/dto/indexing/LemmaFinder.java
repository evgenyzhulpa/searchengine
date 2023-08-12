package searchengine.dto.indexing;

import lombok.RequiredArgsConstructor;
import org.apache.lucene.morphology.LuceneMorphology;
import org.apache.lucene.morphology.russian.RussianLuceneMorphology;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@RequiredArgsConstructor
public class LemmaFinder {
    @Autowired
    private final LuceneMorphology luceneMorphology;
    private final String[] particlesNames = {"ПРЕДЛ", "СОЮЗ", "МЕЖД"};

    public static LemmaFinder getInstance() throws IOException {
        LuceneMorphology morphology = new RussianLuceneMorphology();
        return new LemmaFinder(morphology);
    }

    public HashMap<String, Integer> getLemmas(String text)
    {
        HashMap<String, Integer> lemmas = new HashMap<>();
        text = clearHtmlTags(text);
        String[] words = arrayOfRussianWords(text);

        for (String word : words) {
            List<String> normalForms;
            String normalForm;

            word = word.replaceAll("\\s", "");
            if (word.isBlank() || anyWordBaseBelongToParticle(word)) {
                continue;
            }
            normalForms = luceneMorphology.getNormalForms(word);
            normalForm = normalForms.get(0);
            if (lemmas.containsKey(normalForm)) {
                lemmas.put(normalForm, lemmas.get(normalForm) + 1);
            } else {
                lemmas.put(normalForm, 1);
            }
        }
        return lemmas;
    }

    public Set<String> wordsByLemmas(String text, Set<String> lemmas)
    {
        text = clearHtmlTags(text);
        String[] words = text
                .replaceAll("[^А-Яа-я]", " ")
                .split(" ");
        ;

        return Arrays.stream(words)
                .filter(word -> !(word.isBlank() || anyWordBaseBelongToParticle(word.toLowerCase(Locale.ROOT))))
                .filter(word -> {
                    String normalForm = luceneMorphology.getNormalForms(word.toLowerCase(Locale.ROOT)).get(0);
                    return lemmas.contains(normalForm);
                })
                .collect(Collectors.toSet());
    }


    private String[] arrayOfRussianWords(String text)
    {
        return text
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^а-я]", " ")
                .split(" ");
    }

    private boolean anyWordBaseBelongToParticle(String word) {
        List<String> wordBaseForms = luceneMorphology.getMorphInfo(word);
        return wordBaseForms.stream().anyMatch(this::isParticle);
    }

    private boolean isParticle(String word) {
       for (String particleName : particlesNames) {
            if (word.contains(particleName)) {
                return true;
            }
        }
        return false;
    }

    public String clearHtmlTags(String text) {
        String regex = "<[^>]+>|<[^/>]+/>";
        return text.replaceAll(regex, "");
    }
}
