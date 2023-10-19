package searchengine.dto.indexing;

import lombok.RequiredArgsConstructor;
import org.apache.lucene.morphology.LuceneMorphology;
import org.apache.lucene.morphology.russian.RussianLuceneMorphology;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RequiredArgsConstructor
public class LemmaFinder {
    @Autowired
    private final LuceneMorphology luceneMorphology;
    private final String[] particlesNames = {"ПРЕДЛ", "СОЮЗ", "МЕЖД"};

    public static LemmaFinder getInstance() throws IOException {
        LuceneMorphology morphology = new RussianLuceneMorphology();
        return new LemmaFinder(morphology);
    }

    public HashMap<String, Integer> getLemmasAndTheirFrequencies(String htmlContent) {
        HashMap<String, Integer> lemmas = new HashMap<>();
        String[] words = arrayOfRussianWords(htmlContent);

        for (String word : words) {
            String normalForm = getNormalFormOfWord(word);
            if (normalForm.isBlank()) {
                continue;
            }
            if (lemmas.containsKey(normalForm)) {
                lemmas.put(normalForm, lemmas.get(normalForm) + 1);
            } else {
                lemmas.put(normalForm, 1);
            }
        }
        return lemmas;
    }

    public Set<String> getLemmasFromWords(String text) {
        Set<String> lemmas = new HashSet<>();
        String[] words = arrayOfRussianWords(text);

        for (String word : words) {
            String normalForm = getNormalFormOfWord(word);
            if (normalForm.isBlank()) {
                continue;
            }
            lemmas.add(normalForm);
        }
        return lemmas;
    }

    public Set<String> getWordsByLemmas(Set<String> lemmas, String text) {
        Set<String> words = new HashSet<>();
        String[] allWords = getWordsArrayFromText(text);

        for (String word : allWords) {
            String normalForm = getNormalFormOfWord(word);
            if (!normalForm.isBlank() && lemmas.contains(normalForm)) {
                words.add(word);
            }
        }
        return words;
    }

    private String[] getWordsArrayFromText(String text) {
        String[] words = arrayOfRussianWords(text);
        return words;
    }

    public String getNormalFormOfWord(String word) {
        word = word.replaceAll("\\s", "");
        if (word.isBlank() || anyWordBaseBelongToParticle(word)) {
            return "";
        }
        List<String> normalForms = luceneMorphology.getNormalForms(word);
        return normalForms.get(0);
    }

    private String[] arrayOfRussianWords(String text) {
        Set<String> russianWords = new HashSet<>();
        Pattern pattern = Pattern.compile("[а-я]+");
        Matcher matcher = pattern.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            russianWords.add(matcher.group());
        }
        if (russianWords.isEmpty()) {
            return new String[0];
        }

        return russianWords.toArray(new String[russianWords.size()]);
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
}
