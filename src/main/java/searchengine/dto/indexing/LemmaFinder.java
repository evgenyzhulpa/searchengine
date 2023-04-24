package searchengine.dto.indexing;

import lombok.RequiredArgsConstructor;
import org.apache.lucene.morphology.LuceneMorphology;
import org.apache.lucene.morphology.russian.RussianLuceneMorphology;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

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

            if (word.isBlank() || anyWordBaseBelongToParticle(word)) {
                return lemmas;
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

    private String[] arrayOfRussianWords(String text)
    {
        return text
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^а-я\\s]", "")
                .split(" ");
    }

    private boolean anyWordBaseBelongToParticle(String word) {
        List<String> wordBaseForms = luceneMorphology.getMorphInfo(word);
        return wordBaseForms.stream().anyMatch(this::isParticle);
    }

    private boolean isParticle(String word) {
        for (String particleName : particlesNames) {
            return word.contains(particleName);
        }
        return false;
    }

    private String clearHtmlTags(String text) {
        String regex = "<[^>]+>|<[^/>]+/>";
        return text.replaceAll(regex, "");
    }
}
